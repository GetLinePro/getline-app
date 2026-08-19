package pro.getline.vpn.getline.share

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.PersistableBundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.SubscriptionUiState
import pro.getline.vpn.getlineui.R as GetLineUiR

/**
 * User-initiated export of the current subscription URL: QR + clipboard copy.
 * Payload is the stored managed source only — not account/session identifiers.
 */
object SubscriptionLinkShare {
    private const val QR_SIZE_PX = 512
    private const val CLIP_LABEL = "subscription"

    /** Main-thread only ([showDialog] runs under [Dispatchers.Main]). */
    private var openDialog: AlertDialog? = null

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
        encodeQr: (String) -> Bitmap? = ::encodeQr,
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

    internal fun encodeQr(contents: String): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            hints,
        )
        val pixels = IntArray(QR_SIZE_PX * QR_SIZE_PX)
        var i = 0
        for (y in 0 until QR_SIZE_PX) {
            for (x in 0 until QR_SIZE_PX) {
                pixels[i++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, QR_SIZE_PX, 0, 0, QR_SIZE_PX, QR_SIZE_PX)
        }
    }

    internal fun copyToClipboard(context: Context, url: String) {
        val clipboard = context.getSystemService<ClipboardManager>() ?: return
        val clip = ClipData.newPlainText(CLIP_LABEL, url)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                context,
                context.getString(GetLineUiR.string.get_line_share_subscription_copied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showDialog(activity: Activity, url: String, qr: Bitmap?) {
        openDialog?.let { previous ->
            openDialog = null
            if (previous.isShowing) {
                runCatching { previous.dismiss() }
            }
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(GetLineUiR.string.get_line_share_subscription)
            .setPositiveButton(GetLineUiR.string.get_line_share_subscription_copy) { _, _ ->
                copyToClipboard(activity, url)
            }
            .setNegativeButton(GetLineUiR.string.cancel, null)
        if (qr != null) {
            val density = activity.resources.displayMetrics.density
            val pad = (20 * density).toInt()
            val qrSize = (240 * density).toInt()
            val image = ImageView(activity).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                contentDescription =
                    activity.getString(GetLineUiR.string.get_line_share_subscription_qr)
                setImageBitmap(qr)
            }
            builder.setView(
                FrameLayout(activity).apply {
                    setPadding(pad, pad / 2, pad, pad / 2)
                    addView(
                        image,
                        FrameLayout.LayoutParams(qrSize, qrSize).apply {
                            gravity = android.view.Gravity.CENTER
                        },
                    )
                },
            )
        }
        val dialog = builder.create()

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
    }
}
