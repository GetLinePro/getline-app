package pro.getline.vpn.getline.share

import android.app.Activity
import android.app.Application
import android.content.ClipDescription
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import pro.getline.vpn.getline.auth.SubscriptionPresentation
import pro.getline.vpn.getline.auth.SubscriptionUiState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SubscriptionLinkShareTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val exactUrl =
        "https://sub.example.test/link?token=AbC%2Fraw-value&x=1"

    @Test
    fun resolve_readyMatchingUuidAndSource_returnsExactSource() {
        val resolved = SubscriptionLinkShare.resolve(
            state = ready(id = "managed-uuid"),
            managedUuid = "managed-uuid",
            managedSource = exactUrl,
        )
        assertEquals(exactUrl, resolved)
    }

    @Test
    fun resolve_doesNotRewriteOrWrapTheSource() {
        val odd = "https://LEGACY.Example.TEST:8443/a/b?token=a+b c"
        assertEquals(
            odd,
            SubscriptionLinkShare.resolve(
                state = ready(id = "u"),
                managedUuid = "u",
                managedSource = odd,
            ),
        )
    }

    @Test
    fun resolve_blankOrMissingSource_isUnavailable() {
        val ready = ready(id = "u")
        assertNull(
            SubscriptionLinkShare.resolve(ready, "u", null),
        )
        assertNull(
            SubscriptionLinkShare.resolve(ready, "u", ""),
        )
        assertNull(
            SubscriptionLinkShare.resolve(ready, "u", "  "),
        )
    }

    @Test
    fun resolve_uuidMismatchOrMissingId_isUnavailable() {
        assertNull(
            SubscriptionLinkShare.resolve(
                state = ready(id = "card"),
                managedUuid = "other",
                managedSource = exactUrl,
            ),
        )
        assertNull(
            SubscriptionLinkShare.resolve(
                state = ready(id = null),
                managedUuid = "u",
                managedSource = exactUrl,
            ),
        )
        assertNull(
            SubscriptionLinkShare.resolve(
                state = ready(id = "u"),
                managedUuid = null,
                managedSource = exactUrl,
            ),
        )
    }

    @Test
    fun resolve_notReady_isUnavailable() {
        val source = exactUrl
        val uuid = "u"
        assertNull(SubscriptionLinkShare.resolve(SubscriptionUiState.Loading, uuid, source))
        assertNull(SubscriptionLinkShare.resolve(SubscriptionUiState.Empty, uuid, source))
        assertNull(SubscriptionLinkShare.resolve(SubscriptionUiState.Failed, uuid, source))
    }

    @Test
    fun resolve_readyWhileRefreshing_stillShares() {
        assertEquals(
            exactUrl,
            SubscriptionLinkShare.resolve(
                state = ready(id = "u", refreshing = true),
                managedUuid = "u",
                managedSource = exactUrl,
            ),
        )
    }

    @Test
    fun encodeQr_brandedInvertedQrRoundTripsExactPayload() {
        val bitmap = SubscriptionLinkShare.encodeQr(context, exactUrl)
        assertEquals(exactUrl, decodeQr(bitmap))
        assertTrue(bitmapCenterContainsWordmark(bitmap))
    }

    @Test
    fun encodeQr_brandedInvertedQrRoundTripsDensePayload() {
        val denseUrl = "https://sub.example.test/link?token=" + "AbC123xyz".repeat(28)
        val bitmap = SubscriptionLinkShare.encodeQr(context, denseUrl)
        assertEquals(denseUrl, decodeQr(bitmap))
    }

    @Test
    @Config(sdk = [23])
    fun copy_api23_writesExactTextWithoutExtras() {
        SubscriptionLinkShare.copyToClipboard(context, exactUrl)
        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertNotNull(clip)
        assertEquals(exactUrl, clip!!.getItemAt(0).text.toString())
        assertEquals("subscription", clip.description.label.toString())
        assertEquals(
            context.getString(pro.getline.vpn.getlineui.R.string.get_line_share_subscription_copied),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    @Config(sdk = [24])
    fun copy_api24_marksClipSensitive() {
        SubscriptionLinkShare.copyToClipboard(context, exactUrl)
        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertNotNull(clip)
        assertEquals(exactUrl, clip!!.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertEquals(
            context.getString(pro.getline.vpn.getlineui.R.string.get_line_share_subscription_copied),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    @Config(sdk = [33])
    fun copy_api33_doesNotShowAppToast() {
        ShadowToast.reset()
        SubscriptionLinkShare.copyToClipboard(context, exactUrl)
        val clip = context.getSystemService<ClipboardManager>()!!.primaryClip
        assertEquals(exactUrl, clip!!.getItemAt(0).text.toString())
        assertTrue(clip.description.extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun present_encoderFailure_showsCopyOnlyDialogWithSameUrl() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertFalse(activity.isFinishing)

        var shown = false
        var shownUrl: String? = null
        var shownQr: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        runBlocking {
            SubscriptionLinkShare.present(
                activity = activity,
                url = exactUrl,
                encodeQr = { error("encoder failed") },
                show = { _, url, qr ->
                    shown = true
                    shownUrl = url
                    shownQr = qr
                },
            )
        }

        assertTrue(shown)
        assertEquals(exactUrl, shownUrl)
        assertNull(shownQr)
    }

    private fun decodeQr(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
        )
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }

    private fun bitmapCenterContainsWordmark(bitmap: Bitmap): Boolean {
        val background = ContextCompat.getColor(
            context,
            pro.getline.vpn.getlineui.R.color.getline_brand_background,
        )
        val left = (bitmap.width * 0.37f).toInt()
        val right = (bitmap.width * 0.63f).toInt()
        val top = (bitmap.height * 0.47f).toInt()
        val bottom = (bitmap.height * 0.53f).toInt()
        for (y in top until bottom) {
            for (x in left until right) {
                if (bitmap.getPixel(x, y) != background) return true
            }
        }
        return false
    }

    private fun ready(
        id: String?,
        refreshing: Boolean = false,
    ): SubscriptionUiState.Ready {
        return SubscriptionUiState.Ready(
            subscription = SubscriptionPresentation(
                id = id,
                title = "Standard",
                isActive = true,
                expireAtEpochMillis = 1_700_000_000_000L,
                daysLeft = 2,
                deviceLimit = 3,
                trafficUsedBytes = 100L,
                trafficLimitBytes = 1000L,
                trafficUnlimited = false,
            ),
            isRefreshing = refreshing,
        )
    }
}
