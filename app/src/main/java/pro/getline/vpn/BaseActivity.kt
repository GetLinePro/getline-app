package pro.getline.vpn

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import pro.getline.vpn.common.compat.isAllowForceDarkCompat
import pro.getline.vpn.common.compat.isLightNavigationBarCompat
import pro.getline.vpn.common.compat.isLightStatusBarsCompat
import pro.getline.vpn.common.compat.isSystemBarsTranslucentCompat
import pro.getline.vpn.core.bridge.ClashException
import pro.getline.vpn.design.Design
import pro.getline.vpn.design.store.UiStore
import pro.getline.vpn.design.util.resolveThemedBoolean
import pro.getline.vpn.design.util.resolveThemedColor
import pro.getline.vpn.design.util.showExceptionToast
import pro.getline.vpn.remote.Broadcasts
import pro.getline.vpn.remote.Remote
import pro.getline.vpn.util.ActivityResultLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import pro.getline.vpn.design.R

abstract class BaseActivity<D : Design<*>> : AppCompatActivity(),
    CoroutineScope by MainScope(),
    Broadcasts.Observer {
    
    protected val uiStore by lazy { UiStore(this) }
    protected val events = Channel<Event>(Channel.UNLIMITED)
    protected var activityStarted: Boolean = false
    protected val clashRunning: Boolean
        get() = Remote.broadcasts.clashRunning
    protected var design: D? = null
        set(value) {
            field = value
            if (value != null) {
                setContentView(value.root)
            } else {
                setContentView(View(this))
            }
        }

    private var defer: suspend () -> Unit = {}
    private var deferRunning = false
    private val nextRequestKey = AtomicInteger(0)

    protected abstract suspend fun main()

    fun defer(operation: suspend () -> Unit) {
        this.defer = operation
    }

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

    override fun finish() {
        if (deferRunning) return
        deferRunning = true

        launch {
            try {
                defer()
            } finally {
                withContext(NonCancellable) {
                    super.finish()
                }
            }
        }
    }

    open fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return true
    }

    protected open fun handleBackPressed() {
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
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

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showExceptionToast(ClashException(cause))
            }
        }
    }

    /**
     * The product contour is dark only, so the system night setting is ignored.
     * BootstrapTheme is dark as well, which keeps the pre-onCreate frame from
     * flashing light.
     */
    private fun applyTheme() {
        theme.applyStyle(R.style.AppThemeDark, true)

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
