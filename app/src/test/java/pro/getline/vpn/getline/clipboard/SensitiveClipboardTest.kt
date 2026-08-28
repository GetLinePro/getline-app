package pro.getline.vpn.getline.clipboard

import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.getSystemService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import pro.getline.vpn.getlineui.R as GetLineUiR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SensitiveClipboardTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val secret = "p@ss/word:with-punctuation"

    private val confirmation = GetLineUiR.string.get_line_share_subscription_copied

    @Before
    fun resetToasts() {
        ShadowToast.reset()
    }

    @Test
    @Config(sdk = [23])
    fun beforeApi24_theExactValueIsCopiedAndConfirmed() {
        assertTrue(SensitiveClipboard.copy(context, "password", secret, confirmation))

        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertNotNull(clip)
        assertEquals(secret, clip!!.getItemAt(0).text.toString())
        assertEquals("password", clip.description.label.toString())
        assertEquals(
            context.getString(confirmation),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    /**
     * The flag is what keeps a password out of the launcher's clipboard
     * preview, so it is checked on the first API that understands it.
     */
    @Test
    @Config(sdk = [24])
    fun fromApi24_theClipIsMarkedSensitive() {
        assertTrue(SensitiveClipboard.copy(context, "password", secret, confirmation))

        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertNotNull(clip)
        assertEquals(secret, clip!!.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    /** Android 13 confirms copies itself; a second toast would read as a bug. */
    @Test
    @Config(sdk = [33])
    fun fromApi33_theAppAddsNoConfirmationOfItsOwn() {
        assertTrue(SensitiveClipboard.copy(context, "password", secret, confirmation))

        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertEquals(secret, clip!!.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertNull(ShadowToast.getLatestToast())
    }

    /**
     * A copy that did not happen must not be confirmed: the caller renders the
     * failure instead of a "Copied" the user cannot act on.
     */
    @Test
    fun withoutAClipboardServiceNothingIsCopiedOrConfirmed() {
        val withoutClipboard = object : ContextWrapper(context) {
            // Context.getSystemService(Class) is final and resolves through
            // this one, so refusing the name refuses both forms.
            override fun getSystemService(name: String): Any? =
                if (name == Context.CLIPBOARD_SERVICE) null else super.getSystemService(name)
        }

        assertFalse(SensitiveClipboard.copy(withoutClipboard, "password", secret, confirmation))
        assertNull(ShadowToast.getLatestToast())
    }

    /** Nothing is scheduled to wipe the clipboard behind the user's paste. */
    @Test
    fun theCopiedValueStaysOnTheClipboard() {
        assertTrue(SensitiveClipboard.copy(context, "password", secret, confirmation))

        repeat(3) { org.robolectric.shadows.ShadowLooper.idleMainLooper() }

        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertEquals(secret, clip!!.getItemAt(0).text.toString())
    }
}
