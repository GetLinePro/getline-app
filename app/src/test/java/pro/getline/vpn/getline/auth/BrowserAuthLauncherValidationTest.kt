package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthLauncherValidationTest {
    @Test
    fun validHttpsUrl_accepted() {
        val uri = BrowserAuthLauncher.parseAndValidateLaunchUrl(
            "https://accounts.google.com/o/oauth2/auth?x=1",
        )
        assertEquals("accounts.google.com", uri.host)
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
