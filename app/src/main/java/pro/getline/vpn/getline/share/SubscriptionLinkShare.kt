package pro.getline.vpn.getline.share

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.SubscriptionUiState
import pro.getline.vpn.getline.clipboard.SensitiveClipboard
import pro.getline.vpn.getlineui.R as GetLineUiR

/**
 * User-initiated export of the current subscription URL: QR + clipboard copy.
 * Payload is the stored managed source only — not account/session identifiers.
 */
object SubscriptionLinkShare {
    private const val QR_SIZE_PX = 512
    private const val QR_MARGIN_MODULES = 4
    private const val BRAND_BADGE_WIDTH_RATIO = 0.34f
    private const val BRAND_BADGE_HEIGHT_RATIO = 0.10f
    private const val BRAND_WORDMARK_WIDTH_RATIO = 0.86f
    private const val DIALOG_MAX_WIDTH_DP = 360
    private const val DIALOG_HORIZONTAL_MARGIN_DP = 16
    private const val CLIP_LABEL = "subscription"

    /** Main-thread only ([showDialog] runs under [Dispatchers.Main]). */
    private var openDialog: Dialog? = null

    fun resolve(
        state: SubscriptionUiState,
        managedUuid: String?,
        managedSource: String?,
    ): String? {
        val ready = state as? SubscriptionUiState.Ready ?: return null
        val cardId = ready.subscription.id ?: return null
        val uuid = managedUuid ?: return null
        if (cardId != uuid) return null
        return managedSource?.takeIf { it.isNotBlank() }
    }

    suspend fun present(
        activity: Activity,
        url: String,
        encodeQr: (String) -> Bitmap? = { contents -> encodeQr(activity, contents) },
        show: (Activity, String, Bitmap?) -> Unit = ::showDialog,
    ) {
        val qr = withContext(Dispatchers.Default) {
            runCatching { encodeQr(url) }.getOrNull()
        }
        withContext(Dispatchers.Main.immediate) {
            if (activity.isFinishing) return@withContext
            show(activity, url, qr)
        }
    }

    internal fun encodeQr(context: Context, contents: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to QR_MARGIN_MODULES,
        )
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            hints,
        )
        val foreground = ContextCompat.getColor(
            context,
            GetLineUiR.color.getline_brand_primary,
        )
        val background = ContextCompat.getColor(
            context,
            GetLineUiR.color.getline_brand_background,
        )
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX)
        var i = 0
        for (y in 0 until QR_SIZE_PX) {
            for (x in 0 until QR_SIZE_PX) {
                pixels[i++] = if (matrix.get(x, y)) foreground else background
            }
        }
        return Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, QR_SIZE_PX, 0, 0, QR_SIZE_PX, QR_SIZE_PX)
            drawBrandLockup(context, Canvas(it), background)
        }
    }

    private fun drawBrandLockup(context: Context, canvas: Canvas, background: Int) {
        val wordmark = AppCompatResources.getDrawable(
            context,
            GetLineUiR.drawable.ic_getline_wordmark,
        ) ?: return

        val badgeWidth = (QR_SIZE_PX * BRAND_BADGE_WIDTH_RATIO).roundToInt()
        val badgeHeight = (QR_SIZE_PX * BRAND_BADGE_HEIGHT_RATIO).roundToInt()
        val badgeLeft = (QR_SIZE_PX - badgeWidth) / 2f
        val badgeTop = (QR_SIZE_PX - badgeHeight) / 2f
        val badge = RectF(
            badgeLeft,
            badgeTop,
            badgeLeft + badgeWidth,
            badgeTop + badgeHeight,
        )
        canvas.drawRoundRect(
            badge,
            badgeHeight / 4f,
            badgeHeight / 4f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background },
        )

        val wordmarkWidth = (badgeWidth * BRAND_WORDMARK_WIDTH_RATIO).roundToInt()
        val wordmarkHeight = (
            wordmarkWidth * wordmark.intrinsicHeight.toFloat() / wordmark.intrinsicWidth
        ).roundToInt()
        val wordmarkLeft = (QR_SIZE_PX - wordmarkWidth) / 2
        val wordmarkTop = (QR_SIZE_PX - wordmarkHeight) / 2
        wordmark.setBounds(
            wordmarkLeft,
            wordmarkTop,
            wordmarkLeft + wordmarkWidth,
            wordmarkTop + wordmarkHeight,
        )
        wordmark.draw(canvas)
    }

    internal fun copyToClipboard(context: Context, url: String) {
        SensitiveClipboard.copy(
            context = context,
            label = CLIP_LABEL,
            value = url,
            confirmation = GetLineUiR.string.get_line_share_subscription_copied,
        )
    }

    private fun showDialog(activity: Activity, url: String, qr: Bitmap?) {
        openDialog?.let { previous ->
            openDialog = null
            if (previous.isShowing) {
                runCatching { previous.dismiss() }
            }
        }

        val content = activity.layoutInflater.inflate(
            GetLineUiR.layout.dialog_get_line_share_subscription,
            null,
            false,
        )
        ViewCompat.setAccessibilityPaneTitle(
            content,
            activity.getString(GetLineUiR.string.get_line_share_subscription),
        )
        val qrContainer = content.findViewById<View>(GetLineUiR.id.get_line_share_qr_container)
        val qrImage = content.findViewById<ImageView>(GetLineUiR.id.get_line_share_qr)
        if (qr != null) {
            qrImage.setImageBitmap(qr)
        } else {
            qrContainer.visibility = View.GONE
        }

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            setCanceledOnTouchOutside(true)
        }
        content.findViewById<View>(GetLineUiR.id.get_line_share_cancel).setOnClickListener {
            dialog.dismiss()
        }
        content.findViewById<View>(GetLineUiR.id.get_line_share_copy).setOnClickListener {
            copyToClipboard(activity, url)
            dialog.dismiss()
        }

        val lifecycleObserver = if (activity is LifecycleOwner) {
            object : DefaultLifecycleObserver {
                override fun onDestroy(source: LifecycleOwner) {
                    dialog.dismiss()
                    source.lifecycle.removeObserver(this)
                }
            }.also { activity.lifecycle.addObserver(it) }
        } else {
            null
        }

        dialog.setOnDismissListener {
            if (openDialog === dialog) openDialog = null
            if (activity is LifecycleOwner && lifecycleObserver != null) {
                activity.lifecycle.removeObserver(lifecycleObserver)
            }
        }

        openDialog = dialog
        dialog.show()
        val density = activity.resources.displayMetrics.density
        val horizontalMargin = (DIALOG_HORIZONTAL_MARGIN_DP * density).roundToInt()
        val availableWidth = activity.resources.displayMetrics.widthPixels - 2 * horizontalMargin
        val maxWidth = (DIALOG_MAX_WIDTH_DP * density).roundToInt()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.72f)
            setLayout(
                min(maxWidth, availableWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }
}
