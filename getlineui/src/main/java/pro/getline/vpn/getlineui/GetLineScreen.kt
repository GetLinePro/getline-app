package pro.getline.vpn.getlineui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import pro.getline.vpn.getlineui.ui.Surface
import pro.getline.vpn.getlineui.util.setOnInsertsChangedListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

/**
 * Product screen base. Subset of [Design] used by GetLine product UIs.
 * Reuses [Surface] and inset listener from :design (layouts bind surface.insets).
 * Toast lengths use product [ToastDuration], not design.ui.ToastDuration.
 */
abstract class GetLineScreen<R>(val context: Context) :
    CoroutineScope by CoroutineScope(Dispatchers.Unconfined) {

    abstract val root: View

    val surface = Surface()
    val requests: Channel<R> = Channel(Channel.UNLIMITED)

    /** Product-only; not a package-level extension (avoids shadowing design.util for legacy Designs). */
    protected val layoutInflater: LayoutInflater
        get() = LayoutInflater.from(context)

    /** Content root for inflate parent; named separately from [root] (screen view). */
    protected val contentRoot: ViewGroup?
        get() = when (val ctx = context) {
            is Activity -> ctx.findViewById(android.R.id.content)
            else -> null
        }

    suspend fun showToast(
        resId: Int,
        duration: ToastDuration,
        configure: Snackbar.() -> Unit = {},
    ) {
        showToast(context.getString(resId), duration, configure)
    }

    suspend fun showToast(
        message: CharSequence,
        duration: ToastDuration,
        configure: Snackbar.() -> Unit = {},
    ) {
        withContext(Dispatchers.Main) {
            Snackbar.make(
                root,
                message,
                when (duration) {
                    ToastDuration.Short -> Snackbar.LENGTH_SHORT
                    ToastDuration.Long -> Snackbar.LENGTH_LONG
                    ToastDuration.Indefinite -> Snackbar.LENGTH_INDEFINITE
                },
            ).apply(configure).also { currentToast = it }.show()
        }
    }

    /**
     * Toast currently on screen, if any. Kept only so a screen with its own
     * touch handling can leave the toast alone; cleared implicitly by
     * [Snackbar.isShown] going false.
     */
    private var currentToast: Snackbar? = null

    /**
     * True when [event] is over a visible toast. Layouts root at a
     * CoordinatorLayout, so a toast comes with its own horizontal
     * swipe-to-dismiss — a screen watching touches for its own gestures must
     * not take that swipe away.
     */
    protected fun isOverVisibleToast(event: MotionEvent): Boolean {
        val view = currentToast?.takeIf { it.isShown }?.view ?: return false
        val origin = IntArray(2)
        view.getLocationOnScreen(origin)
        return event.rawX >= origin[0] && event.rawX < origin[0] + view.width &&
            event.rawY >= origin[1] && event.rawY < origin[1] + view.height
    }

    init {
        when (context) {
            is AppCompatActivity -> {
                context.window.decorView.setOnInsertsChangedListener {
                    if (surface.insets != it) {
                        surface.insets = it
                    }
                }
            }
        }
    }
}

/**
 * Product toast length. Distinct from design.ui.ToastDuration (CMFA Design).
 * Mapped to Snackbar lengths inside [GetLineScreen.showToast].
 */
enum class ToastDuration {
    Short,
    Long,
    Indefinite,
}

