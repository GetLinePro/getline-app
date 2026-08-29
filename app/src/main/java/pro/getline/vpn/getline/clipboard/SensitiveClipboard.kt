package pro.getline.vpn.getline.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import com.github.kr328.clash.common.log.Log

/**
 * Copying a secret to the clipboard, with the three behaviours the product
 * settled on when subscription links became copyable. Extracted here now that
 * there is a second caller; it is deliberately UI-side, not part of any
 * operational facade — what lands on the clipboard is a screen's decision.
 *
 * The rules, and why:
 *
 * - The clip is flagged sensitive from API 24, so the launcher, the API 33+
 *   clipboard preview and other listeners obscure it instead of rendering the
 *   secret in a floating card.
 * - The app confirms the copy only below API 33, because from Android 13 the
 *   system shows its own confirmation and a second toast reads as a bug.
 * - Nothing is ever cleared afterwards. A timed clear cannot be honest — the
 *   value may already have been pasted, and other apps may hold their own
 *   copy — and silently emptying the clipboard breaks the paste the user is
 *   in the middle of.
 */
object SensitiveClipboard {
    /**
     * Puts [value] on the clipboard under [label], returning whether it got
     * there. False means nothing was copied — no clipboard service, or the
     * write was refused — and the caller should say so rather than showing a
     * confirmation for a copy that did not happen.
     *
     * [confirmation] is the string shown below API 33; it is resolved only
     * when it is actually shown.
     */
    @MainThread
    fun copy(
        context: Context,
        label: String,
        value: String,
        @StringRes confirmation: Int,
    ): Boolean {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false

        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }

        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            // Never log the value: this is called with passwords.
            Log.w("Clipboard copy of '$label' failed: ${e.javaClass.simpleName}", e)

            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
        }

        return true
    }
}
