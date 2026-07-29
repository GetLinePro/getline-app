package pro.getline.vpn.getlineui

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
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
            ).apply(configure).show()
        }
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

