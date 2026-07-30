package com.github.kr328.clash

import android.app.AlertDialog
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import com.github.kr328.clash.design.R
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.GetLineOnboardingActivity
import pro.getline.vpn.getline.GetLineSubscriptionType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ExternalEntryPointTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun foreignSubscriptionHost_isRejectedBeforeOnboarding() {
        val activity = launchExternalImport("https://evil.example/subscription")

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        assertEquals(
            context.getString(R.string.external_import_invalid_link),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun allowedSubscriptionHost_requiresConfirmationAndShowsOnlyHost() {
        val source = "https://${allowedSubscriptionHost()}/subscription?token=secret-value"
        val activity = launchExternalImport(
            source = source,
            name = "Trusted GetLine subscription",
            updateInterval = "30",
        )
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        assertFalse(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(dialog.isShowing)
        val message = shadowOf(dialog).message.toString()
        assertTrue(message.contains(allowedSubscriptionHost()))
        assertFalse(message.contains("secret-value"))
        assertFalse(message.contains("/subscription"))

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(
            GetLineOnboardingActivity::class.java.name,
            started.component?.className,
        )
        assertEquals(
            GetLineSubscriptionType.Url.name,
            started.getStringExtra(EXTRA_IMPORT_TYPE),
        )
        assertEquals(
            context.getString(R.string.new_profile),
            started.getStringExtra(EXTRA_IMPORT_NAME),
        )
        assertEquals(source, started.getStringExtra(EXTRA_IMPORT_SOURCE))
        assertEquals(
            TimeUnit.MINUTES.toMillis(30L),
            started.getLongExtra(EXTRA_IMPORT_INTERVAL, -1L),
        )
        assertTrue(activity.isFinishing)
    }

    @Test
    fun cancelConfirmation_doesNotStartOnboarding() {
        val activity = launchExternalImport("https://${allowedSubscriptionHost()}/subscription")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()

        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun manifest_exposesOnlyImportEntryPoint() {
        val packageManager = context.packageManager
        val control = packageManager.getActivityInfo(
            ComponentName(context, ExternalControlActivity::class.java),
            PackageManager.GET_META_DATA,
        )
        val externalImport = packageManager.getActivityInfo(
            ComponentName(context, ExternalImportActivity::class.java),
            PackageManager.GET_META_DATA,
        )

        assertFalse(control.exported)
        assertTrue(externalImport.exported)

        val matches = packageManager.queryIntentActivities(
            externalImportIntent("https://${allowedSubscriptionHost()}/subscription"),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        assertTrue(
            matches.any { it.activityInfo.name == ExternalImportActivity::class.java.name },
        )

        listOf(
            "${context.packageName}.action.START_CLASH",
            "${context.packageName}.action.STOP_CLASH",
            "${context.packageName}.action.TOGGLE_CLASH",
        ).forEach { action ->
            val controlMatches = packageManager.queryIntentActivities(
                Intent(action).setPackage(context.packageName),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            assertTrue("$action must not resolve an exported Activity", controlMatches.isEmpty())
        }
    }

    private fun launchExternalImport(
        source: String,
        name: String? = null,
        updateInterval: String? = null,
    ): ExternalImportActivity =
        Robolectric.buildActivity(
            ExternalImportActivity::class.java,
            externalImportIntent(source, name, updateInterval),
        ).setup().get()

    private fun externalImportIntent(
        source: String,
        name: String? = null,
        updateInterval: String? = null,
    ): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.Builder()
                .scheme("getline")
                .authority("install-config")
                .appendQueryParameter("url", source)
                .apply {
                    name?.let { appendQueryParameter("name", it) }
                    updateInterval?.let { appendQueryParameter("update-interval", it) }
                }
                .build(),
        )

    private fun allowedSubscriptionHost(): String =
        if (GetLineControlPlaneHostPolicy.isE2e) {
            "app.stage.getline.pro"
        } else {
            "sub.getline.pro"
        }

    private companion object {
        const val EXTRA_IMPORT_TYPE = "pro.getline.vpn.extra.GET_LINE_IMPORT_TYPE"
        const val EXTRA_IMPORT_NAME = "pro.getline.vpn.extra.GET_LINE_IMPORT_NAME"
        const val EXTRA_IMPORT_SOURCE = "pro.getline.vpn.extra.GET_LINE_IMPORT_SOURCE"
        const val EXTRA_IMPORT_INTERVAL = "pro.getline.vpn.extra.GET_LINE_IMPORT_INTERVAL"
    }
}
