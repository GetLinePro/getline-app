package pro.getline.vpn.diagnostics

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.kr328.clash.BuildConfig
import com.github.kr328.clash.common.compat.versionCodeCompat
import com.github.kr328.clash.design.R as DesignR
import com.github.kr328.clash.log.SystemLogcat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * User-initiated diagnostic report: build → preview (editable) → system share sheet.
 * No first-party upload.
 */
object DiagnosticReportShare {
    /** Main-thread only ([showPreview] runs under [Dispatchers.Main]). */
    private var openPreview: AlertDialog? = null

    suspend fun present(activity: Activity, hasSession: Boolean) {
        val report = withContext(Dispatchers.IO) {
            val raw = SystemLogcat.dumpAppDiagnostics()
            val packageInfo = activity.packageManager.getPackageInfo(activity.packageName, 0)
            val header = DiagnosticReportBuilder.Header(
                versionName = packageInfo.versionName ?: "unknown",
                versionCode = packageInfo.versionCodeCompat,
                channel = BuildConfig.FLAVOR_environment,
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                hasSession = hasSession,
                // ISO-8601 UTC without java.time (minSdk 23; see GetLineAuthModels).
                generatedAt = utcNowIso(),
            )
            DiagnosticReportBuilder.build(header, raw)
        }

        withContext(Dispatchers.Main) {
            if (activity.isFinishing) return@withContext
            showPreview(activity, report)
        }
    }

    private fun showPreview(activity: Activity, report: String) {
        openPreview?.dismiss()
        openPreview = null

        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val edit = EditText(activity).apply {
            setText(report)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
            // Editable on purpose: tester may strip residual lines before share.
            isFocusable = true
            isFocusableInTouchMode = true
        }
        val scroll = NestedScrollView(activity).apply {
            addView(
                edit,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            setPadding(0, pad / 2, 0, 0)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(DesignR.string.diagnostics_report_title)
            .setView(scroll)
            .setNegativeButton(DesignR.string.cancel, null)
            .setPositiveButton(DesignR.string.diagnostics_share) { _, _ ->
                share(activity, edit.text?.toString().orEmpty())
            }
            .create()

        // Dismiss before window teardown — avoids WindowLeaked on Activity destroy.
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
            if (openPreview === dialog) openPreview = null
            if (activity is LifecycleOwner && lifecycleObserver != null) {
                activity.lifecycle.removeObserver(lifecycleObserver)
            }
        }

        openPreview = dialog
        dialog.show()
    }

    private fun share(activity: Activity, body: String) {
        if (body.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(DesignR.string.diagnostics_report_title))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        activity.startActivity(
            Intent.createChooser(
                send,
                activity.getString(DesignR.string.diagnostics_share),
            ),
        )
    }

    private fun utcNowIso(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }
}
