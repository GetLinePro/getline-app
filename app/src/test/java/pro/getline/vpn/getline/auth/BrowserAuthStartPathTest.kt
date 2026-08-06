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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Locks native PKCE query parameters on /start.
 *
 * Server fail-open: missing `app_redirect` still returns 200 and follows the
 * old web path — so parameter names must be unit-tested, not only observed live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthStartPathTest {
    private val redirect = AppEnvironment.nativeCallbackUri
    private val challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    @Test
    fun google_startPath_containsNativePkceParams() {
        val path = RwpGetLineAuthApi.startPath(AuthMethod.Google, redirect, challenge)
        assertTrue(path.startsWith("/api/auth/google/start?"))
        assertQuery(path, "intent", "register")
        assertQuery(path, "app_redirect", redirect)
        assertQuery(path, "code_challenge", challenge)
        assertQuery(path, "code_challenge_method", "S256")
        assertFalse(path.contains("return_to="))
    }

    @Test
    fun telegram_startPath_containsNativePkceParams_noReturnTo() {
        val path = RwpGetLineAuthApi.startPath(AuthMethod.Telegram, redirect, challenge)
        assertTrue(path.startsWith("/api/auth/telegram-oidc/start?"))
        assertQuery(path, "intent", "register")
        assertQuery(path, "app_redirect", redirect)
        assertQuery(path, "code_challenge", challenge)
        assertQuery(path, "code_challenge_method", "S256")
        assertFalse(path.contains("return_to="))
    }

    @Test
    fun startPath_googleAndTelegramAreDistinct() {
        val google = RwpGetLineAuthApi.startPath(AuthMethod.Google, redirect, challenge)
        val telegram = RwpGetLineAuthApi.startPath(AuthMethod.Telegram, redirect, challenge)
        assertTrue(google != telegram)
    }

    @Test
    fun startPath_emailIsRejected() {
        try {
            RwpGetLineAuthApi.startPath(AuthMethod.Email, redirect, challenge)
            fail("expected rejection for Email")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun nativeCallbackUri_usesSingleSlashForm() {
        val uri = AppEnvironment.nativeCallbackUri
        assertTrue(uri.startsWith(AppEnvironment.nativeCallbackScheme + ":/"))
        assertFalse(uri.contains("://"))
        assertTrue(uri.endsWith("/oauth2redirect"))
    }

    @Test
    fun telegramTrampoline_isNotCompletionPath() {
        val url = AppEnvironment.telegramTrampolineUrl
        val path = android.net.Uri.parse(url).path.orEmpty()
        assertTrue(path.startsWith("/android-auth/telegram"))
        assertNotEquals(AuthCallbackParser.HTTPS_REDIRECT_PATH, path)
    }

    private fun assertQuery(path: String, key: String, expected: String) {
        val query = path.substringAfter('?', missingDelimiterValue = "")
        val found = query.split('&').firstOrNull { it.startsWith("$key=") }
            ?: run {
                fail("missing query key $key in $path")
                return
            }
        val raw = found.removePrefix("$key=")
        val decoded = URLDecoder.decode(raw, StandardCharsets.UTF_8.name())
        assertEquals("query $key", expected, decoded)
    }
}
