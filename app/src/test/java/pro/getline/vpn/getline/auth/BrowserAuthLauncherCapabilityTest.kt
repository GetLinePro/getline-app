package pro.getline.vpn.getline.auth

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import androidx.browser.customtabs.CustomTabsService
import com.github.kr328.clash.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthLauncherCapabilityTest {
    /** Installs [packageName] as a Custom Tabs provider that advertises Auth Tab. */
    private fun installAuthTabProvider(packageName: String) {
        val service = ComponentName(packageName, "$packageName.CustomTabsService")
        val packageManager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        packageManager.addOrUpdateService(
            ServiceInfo().apply {
                this.packageName = packageName
                name = service.className
            },
        )
        packageManager.addIntentFilterForService(
            service,
            IntentFilter(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION).apply {
                addCategory(CustomTabsService.CATEGORY_AUTH_TAB)
            },
        )
    }

    /** Makes [packageName] the only activity resolving `http`, i.e. the default browser. */
    private fun installDefaultBrowserActivity(packageName: String) {
        val activity = ComponentName(packageName, "$packageName.BrowserActivity")
        val packageManager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        packageManager.addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                name = activity.className
            },
        )
        packageManager.addIntentFilterForActivity(
            activity,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("http")
                addDataScheme("https")
            },
        )
    }

    @Test
    fun emptyValue_usesAuthTabBeforeLowerRungs() {
        assertEquals("", BuildConfig.GETLINE_FORCE_BROWSER_RUNG)

        val packageName = "com.android.chrome"
        installAuthTabProvider(packageName)

        assertEquals(
            BrowserCapability.AuthTab(packageName),
            BrowserAuthLauncher().resolveBrowserCapability(
                RuntimeEnvironment.getApplication(),
            ),
        )
    }

    /** #112: the Telegram ceiling must drop the Auth Tab rung even when it is available. */
    @Test
    fun customTabCeiling_skipsAvailableAuthTab() {
        val packageName = "com.android.chrome"
        installAuthTabProvider(packageName)

        assertEquals(
            BrowserCapability.CustomTab(packageName),
            BrowserAuthLauncher().resolveBrowserCapability(
                RuntimeEnvironment.getApplication(),
                BrowserRungCeiling.CustomTab,
            ),
        )
    }

    @Test
    fun ceiling_appliesToTelegramOnly() {
        assertEquals(BrowserRungCeiling.CustomTab, browserRungCeilingFor(AuthMethod.Telegram))
        assertEquals(BrowserRungCeiling.AuthTab, browserRungCeilingFor(AuthMethod.Google))
        assertEquals(BrowserRungCeiling.AuthTab, browserRungCeilingFor(AuthMethod.Email))
    }

    /**
     * The default provider gets first refusal on the Auth Tab rung. Guards the
     * `ignoreDefault = false` argument: with `true` this returns Chrome, because
     * it heads PREFERRED_BROWSERS regardless of the user's choice.
     */
    @Test
    fun authTabRung_prefersDefaultBrowserOverPreferredList() {
        installAuthTabProvider("com.android.chrome")
        installAuthTabProvider("org.mozilla.firefox")
        installDefaultBrowserActivity("org.mozilla.firefox")

        assertEquals(
            BrowserCapability.AuthTab("org.mozilla.firefox"),
            BrowserAuthLauncher().resolveBrowserCapability(
                RuntimeEnvironment.getApplication(),
            ),
        )
    }

    @Test
    fun customTabValue_selectsCustomTab() {
        assertEquals(
            BrowserCapability.CustomTab("org.mozilla.firefox"),
            BrowserAuthLauncher.resolveForcedBrowserRung(
                forceBrowserRung = "customtab",
                customTabPackage = "org.mozilla.firefox",
                hasGenericHttpsBrowser = true,
            ),
        )
    }

    @Test
    fun customTabValue_fallsThroughToExternalBrowser() {
        assertEquals(
            BrowserCapability.ExternalBrowser,
            BrowserAuthLauncher.resolveForcedBrowserRung(
                forceBrowserRung = "customtab",
                customTabPackage = null,
                hasGenericHttpsBrowser = true,
            ),
        )
    }

    @Test
    fun externalValue_skipsCustomTab() {
        assertEquals(
            BrowserCapability.ExternalBrowser,
            BrowserAuthLauncher.resolveForcedBrowserRung(
                forceBrowserRung = "external",
                customTabPackage = "com.android.chrome",
                hasGenericHttpsBrowser = true,
            ),
        )
    }

    @Test
    fun releaseBuild_ignoresForceBrowserRungProperty() {
        if (BuildConfig.BUILD_TYPE != "release") return
        assertEquals("", BuildConfig.GETLINE_FORCE_BROWSER_RUNG)
    }
}
