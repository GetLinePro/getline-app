package pro.getline.vpn.getline.auth

import android.content.ComponentName
import android.content.IntentFilter
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
    @Test
    fun emptyValue_usesAuthTabBeforeLowerRungs() {
        assertEquals("", BuildConfig.GETLINE_FORCE_BROWSER_RUNG)

        val packageName = "com.android.chrome"
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

        assertEquals(
            BrowserCapability.AuthTab(packageName),
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
