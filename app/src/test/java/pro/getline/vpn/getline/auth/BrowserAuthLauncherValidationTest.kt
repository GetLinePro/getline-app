package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.GetLineControlPlaneHostPolicy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthLauncherValidationTest {
    @Test
    fun validHttpsUrl_accepted() {
        // Prod allows third-party OAuth; e2e only allows stage product hosts.
        val url = if (GetLineControlPlaneHostPolicy.isE2e) {
            "https://auth.stage.getline.pro/__mock__/google"
        } else {
            "https://accounts.google.com/o/oauth2/auth?x=1"
        }
        val uri = BrowserAuthLauncher.parseAndValidateLaunchUrl(url)
        assertEquals(android.net.Uri.parse(url).host, uri.host)
    }

    @Test
    fun productionHost_rejectedOnE2e() {
        if (!GetLineControlPlaneHostPolicy.isE2e) return
        try {
            BrowserAuthLauncher.parseAndValidateLaunchUrl(
                "https://app.getline.pro/oauth/start",
            )
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun blankUrl_rejected() {
        try {
            BrowserAuthLauncher.parseAndValidateLaunchUrl("  ")
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun nonHttpsScheme_rejected() {
        try {
            BrowserAuthLauncher.parseAndValidateLaunchUrl("javascript:alert(1)")
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun cleartextHttp_rejected() {
        try {
            BrowserAuthLauncher.parseAndValidateLaunchUrl(
                "http://accounts.google.com/o/oauth2/auth?x=1",
            )
            fail("expected Protocol")
        } catch (e: GetLineAuthException.Protocol) {
            assertEquals("auth_url must be https", e.message)
        }
    }
}
