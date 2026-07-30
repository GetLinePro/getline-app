package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthStarterTest {
    @Test
    fun google_usesTrampoline() {
        assertEquals(
            AppEnvironment.googleTrampolineUrl,
            BrowserAuthStarter.resolveAuthUrl(AuthMethod.Google),
        )
    }

    @Test
    fun telegram_usesTrampoline() {
        assertEquals(
            AppEnvironment.telegramTrampolineUrl,
            BrowserAuthStarter.resolveAuthUrl(AuthMethod.Telegram),
        )
    }

    @Test
    fun trampolines_areDistinct() {
        assertNotEquals(
            BrowserAuthStarter.resolveAuthUrl(AuthMethod.Google),
            BrowserAuthStarter.resolveAuthUrl(AuthMethod.Telegram),
        )
    }

    /**
     * A trampoline served from the completion path would let a supporting browser
     * treat the initial navigation as HTTPS completion.
     */
    @Test
    fun trampolines_areNotOnCompletionPath() {
        for (method in listOf(AuthMethod.Google, AuthMethod.Telegram)) {
            val url = BrowserAuthStarter.resolveAuthUrl(method)
            val uri = android.net.Uri.parse(url)
            assertNotEquals(BrowserAuthLauncher.REDIRECT_PATH, uri.path)
            assertTrue(uri.path.orEmpty().startsWith("/android-auth/"))
        }
    }

    @Test
    fun trampolines_passLaunchHostPolicy() {
        for (method in listOf(AuthMethod.Google, AuthMethod.Telegram)) {
            val url = BrowserAuthStarter.resolveAuthUrl(method)
            // Must not throw for the active flavor.
            BrowserAuthLauncher.parseAndValidateLaunchUrl(url)
            assertTrue(
                GetLineControlPlaneHostPolicy.isAllowedBrowserLaunchUrl(url),
            )
        }
    }

    @Test
    fun email_cannotStartBrowserPath() {
        try {
            BrowserAuthStarter.resolveAuthUrl(AuthMethod.Email)
            fail("expected rejection for Email")
        } catch (_: IllegalArgumentException) {
            // require(method.requiresBrowser())
        }
        assertFalse(AuthMethod.Email.requiresBrowser())
    }

    /**
     * Start endpoints are no longer called from the app process — the trampolines
     * call them in-browser — but the paths remain the documented RWP contract the
     * trampoline HTML and the e2e mock implement.
     */
    @Test
    fun startPath_googleAndTelegramAreDistinct() {
        val google = RwpGetLineAuthApi.startPath(AuthMethod.Google)
        val telegram = RwpGetLineAuthApi.startPath(AuthMethod.Telegram)
        assertEquals("/api/auth/google/start", google)
        assertTrue(telegram.startsWith("/api/auth/telegram-oidc/start"))
        assertTrue(telegram.contains("intent=login"))
        assertTrue(google != telegram)
    }

    @Test
    fun startPath_emailIsRejected() {
        try {
            RwpGetLineAuthApi.startPath(AuthMethod.Email)
            fail("expected rejection for Email")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
