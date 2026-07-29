package pro.getline.vpn.product

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.compat.isAllowForceDarkCompat
import com.github.kr328.clash.common.compat.isLightNavigationBarCompat
import com.github.kr328.clash.common.compat.isLightStatusBarsCompat
import com.github.kr328.clash.common.compat.isSystemBarsTranslucentCompat
import pro.getline.vpn.getlineui.GetLineScreen
import pro.getline.vpn.getlineui.R as GetLineUiR
import pro.getline.vpn.getlineui.ToastDuration
import pro.getline.vpn.getlineui.store.GetLineUiStore
import pro.getline.vpn.getlineui.util.resolveThemedBoolean
import pro.getline.vpn.getlineui.util.resolveThemedColor
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.util.ActivityResultLifecycle
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Product Activity base. Mirrors the subset of the CMFA activity host that
 * product screens actually use. Does not pull CMFA core exception types.
 *
 * Intentional differences from the CMFA host:
 * - [finish] is synchronous (CMFA host defers via coroutine + empty defer).
 * - [onStopped] shows product toast, not CMFA exception toast.
 */
abstract class GetLineActivity<D : GetLineScreen<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer {

    protected val uiStore by lazy { GetLineUiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private val nextRequestKey = AtomicInteger(0)

    protected abstract suspend fun main()

    suspend fun <I, O> startActivityForResult(
        contracts: ActivityResultContract<I, O>,
        input: I,
    ): O = withContext(Dispatchers.Main) {
        val requestKey = nextRequestKey.getAndIncrement().toString()

        ActivityResultLifecycle().use { lifecycle, start ->
            suspendCoroutine { c ->
                activityResultRegistry.register(requestKey, lifecycle, contracts) {
                    c.resume(it)
                }.apply { start() }.launch(input)
            }
        }
    }

    /**
     * Post to decorView so [main] can set design before the window is ready.
     * Same post-based handoff as the CMFA host — do not replace with a plain setContentView.
     */
    suspend fun setContentDesign(design: D) {
        suspendCoroutine<Unit> {
            window.decorView.post {
                this.design = design
                it.resume(Unit)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })

        // Apply excludeFromRecents setting to all app tasks.
        checkNotNull(getSystemService<ActivityManager>()).appTasks.forEach { task ->
            task.setExcludeFromRecents(uiStore.hideFromRecents)
        }

        launch {
            main()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        Remote.broadcasts.addObserver(this)
        events.trySend(Event.ActivityStart)
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        Remote.broadcasts.removeObserver(this)
        events.trySend(Event.ActivityStop)
    }

    override fun onDestroy() {
        design?.cancel()
        cancel()
        super.onDestroy()
    }

    protected open fun handleBackPressed() {
        finish()
    }

    override fun onProfileChanged() {
        events.trySend(Event.ProfileChanged)
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        events.trySend(Event.ProfileUpdateCompleted)
    }

    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        events.trySend(Event.ProfileUpdateFailed)
    }

    override fun onProfileLoaded() {
        events.trySend(Event.ProfileLoaded)
    }

    override fun onServiceRecreated() {
        events.trySend(Event.ServiceRecreated)
    }

    override fun onStarted() {
        events.trySend(Event.ClashStart)
    }

    /**
     * Product toast (was Home override). Onboarding inherits this instead of
     * the CMFA exception toast — accepted behavior change for slice 2a.
     */
    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showToast(GetLineUiR.string.get_line_vpn_stopped, ToastDuration.Long)
            }
        }
    }

    /**
     * The product contour is dark only, so the system night setting is ignored.
     * BootstrapTheme is dark as well, which keeps the pre-onCreate frame from
     * flashing light.
     */
    private fun applyTheme() {
        theme.applyStyle(GetLineUiR.style.GetLineTheme, true)

        window.isAllowForceDarkCompat = false
        window.isSystemBarsTranslucentCompat = true

        window.statusBarColor = resolveThemedColor(android.R.attr.statusBarColor)
        window.navigationBarColor = resolveThemedColor(android.R.attr.navigationBarColor)

        if (Build.VERSION.SDK_INT >= 23) {
            window.isLightStatusBarsCompat = resolveThemedBoolean(android.R.attr.windowLightStatusBar)
        }

        if (Build.VERSION.SDK_INT >= 27) {
            window.isLightNavigationBarCompat = resolveThemedBoolean(android.R.attr.windowLightNavigationBar)
        }
    }

    enum class Event {
        ServiceRecreated,
        ActivityStart,
        ActivityStop,
        ClashStop,
        ClashStart,
        ProfileLoaded,
        ProfileChanged,
        ProfileUpdateCompleted,
        ProfileUpdateFailed,
    }
}
