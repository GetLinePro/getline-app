package pro.getline.vpn.getline.accountportal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AccountPortalLauncherTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun successfulCustomTabLaunch_returnsLaunched() {
        val started = mutableListOf<Intent>()
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, intent -> started += intent },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val uri = AccountPortalUriPolicy.dashboardUri()
        val result = launcher.open(context, uri)
        assertEquals(AccountPortalLaunchResult.Launched, result)
        assertEquals(1, started.size)
        assertEquals(uri, started[0].data)
        assertFalse(started[0].hasExtra("Authorization"))
        assertFalse(started[0].hasExtra("android.support.customtabs.extra.EXTRA_HEADERS"))
    }

    @Test
    fun customTabFailure_fallsBackToActionView() {
        var attempt = 0
        val started = mutableListOf<Intent>()
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, intent ->
                attempt++
                if (attempt == 1) {
                    throw ActivityNotFoundException("no custom tabs")
                }
                started += intent
            },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val uri = AccountPortalUriPolicy.dashboardUri()
        val result = launcher.open(context, uri)
        assertEquals(AccountPortalLaunchResult.Launched, result)
        assertEquals(1, started.size)
        assertEquals(Intent.ACTION_VIEW, started[0].action)
        assertEquals(uri, started[0].data)
    }

    @Test
    fun noHandlers_returnsNoBrowserAvailable() {
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, _ -> throw ActivityNotFoundException("none") },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val result = launcher.open(context, AccountPortalUriPolicy.dashboardUri())
        assertEquals(AccountPortalLaunchResult.NoBrowserAvailable, result)
    }

    @Test
    fun invalidUri_doesNotStartIntent() {
        var started = 0
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, _ -> started++ },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val result = launcher.open(
            context,
            Uri.parse("https://evil.example/#/my-dashboard"),
        )
        assertEquals(AccountPortalLaunchResult.RejectedUri, result)
        assertEquals(0, started)
    }

    @Test
    fun httpUri_rejectedWithoutIntent() {
        var started = 0
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, _ -> started++ },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val result = launcher.open(
            context,
            Uri.parse("http://app.getline.pro/#/my-dashboard"),
        )
        assertEquals(AccountPortalLaunchResult.RejectedUri, result)
        assertEquals(0, started)
    }

    @Test
    fun secretInUri_rejectedWithoutIntent() {
        var started = 0
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, _ -> started++ },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val result = launcher.open(
            context,
            Uri.parse("https://app.getline.pro/?access_token=abc"),
        )
        assertEquals(AccountPortalLaunchResult.RejectedUri, result)
        assertEquals(0, started)
    }

    @Test
    fun launcher_doesNotAddAuthorizationHeaders() {
        val started = mutableListOf<Intent>()
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, intent -> started += intent },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        launcher.open(context, AccountPortalUriPolicy.dashboardUri())
        val intent = started.single()
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                assertFalse(
                    "Unexpected auth-related extra: $key",
                    key.equals("Authorization", ignoreCase = true) ||
                        key.equals("auth_token", ignoreCase = true) ||
                        key.equals("access_token", ignoreCase = true) ||
                        key.equals("refresh_token", ignoreCase = true),
                )
            }
        }
        // Browser package must not be hard-pinned to Chrome.
        assertTrue(intent.`package` == null)
    }

    @Test
    fun rapidSecondTap_whileInFlight_returnsAlreadyInProgress() {
        val firstInStart = java.util.concurrent.CountDownLatch(1)
        val releaseFirst = java.util.concurrent.CountDownLatch(1)
        val launcher = DefaultAccountPortalLauncher(
            startActivity = { _, _ ->
                firstInStart.countDown()
                // Hold the first open until the second tap is observed.
                releaseFirst.await(3, java.util.concurrent.TimeUnit.SECONDS)
            },
            buildCustomTabsIntent = { CustomTabsIntent.Builder().build() },
        )
        val uri = AccountPortalUriPolicy.dashboardUri()
        val first = java.util.concurrent.atomic.AtomicReference<AccountPortalLaunchResult>()
        val second = java.util.concurrent.atomic.AtomicReference<AccountPortalLaunchResult>()

        val t1 = Thread { first.set(launcher.open(context, uri)) }
        t1.start()
        assertTrue(firstInStart.await(3, java.util.concurrent.TimeUnit.SECONDS))
        second.set(launcher.open(context, uri))
        releaseFirst.countDown()
        t1.join(5_000)

        assertEquals(AccountPortalLaunchResult.Launched, first.get())
        assertEquals(AccountPortalLaunchResult.AlreadyInProgress, second.get())
    }
}
