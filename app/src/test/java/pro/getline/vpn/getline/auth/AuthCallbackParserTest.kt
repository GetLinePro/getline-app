package pro.getline.vpn.getline.auth

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthCallbackParserTest {
    @Test
    fun parse_nativeCode_success() {
        val uri = Uri.parse("${AppEnvironment.nativeCallbackUri}?code=one-time-code")
        val result = AuthCallbackParser.parse(uri)
        assertTrue(result is AuthCallbackResult.NativeCode)
        assertEquals("one-time-code", (result as AuthCallbackResult.NativeCode).code)
    }

    @Test
    fun parse_nativeAuthToken_success() {
        val uri = Uri.parse(
            "${AppEnvironment.nativeCallbackUri}?auth_token=web-tok&expires_in=60",
        )
        val result = AuthCallbackParser.parse(uri)
        assertTrue(result is AuthCallbackResult.WebToken)
        val web = result as AuthCallbackResult.WebToken
        assertEquals("web-tok", web.authToken)
        assertEquals(60L, web.expiresInSeconds)
    }

    @Test
    fun parse_httpsFragment_webToken() {
        val uri = Uri.parse(
            "https://${AppEnvironment.callbackHost}/#/login?auth_token=frag-tok&expires_in=86400",
        )
        val result = AuthCallbackParser.parse(uri)
        assertTrue(result is AuthCallbackResult.WebToken)
        assertEquals("frag-tok", (result as AuthCallbackResult.WebToken).authToken)
        assertEquals(86400L, result.expiresInSeconds)
    }

    @Test
    fun parse_httpsAuthError_rejected() {
        val uri = Uri.parse(
            "https://${AppEnvironment.callbackHost}/#/login?auth_error=access_denied",
        )
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (e: GetLineAuthException.InvalidCallback) {
            assertTrue(e.message!!.contains("error", ignoreCase = true))
            assertTrue(!e.message!!.contains("access_denied"))
        }
    }

    @Test
    fun parse_nativeError_rejected() {
        val uri = Uri.parse("${AppEnvironment.nativeCallbackUri}?error=access_denied")
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (e: GetLineAuthException.InvalidCallback) {
            assertTrue(!e.message.orEmpty().contains("access_denied"))
        }
    }

    @Test
    fun parse_emptyQuery_rejected() {
        try {
            AuthCallbackParser.parse(Uri.parse(AppEnvironment.nativeCallbackUri))
            fail("expected InvalidCallback")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_doubleSlashForm_rejected() {
        val uri = Uri.parse(
            "${AppEnvironment.nativeCallbackScheme}://oauth2redirect?code=token",
        )
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback for // form")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_wrongHttpsHost_rejected() {
        val uri = Uri.parse("https://evil.example/#/login?auth_token=token")
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_nullUri_rejected() {
        try {
            AuthCallbackParser.parse(null)
            fail("expected InvalidCallback")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_httpsPathMatchesAuthTabRedirect() {
        val uri = Uri.parse(
            "https://${AppEnvironment.callbackHost}${AuthCallbackParser.HTTPS_REDIRECT_PATH}" +
                "#/login?auth_token=contract-token",
        )
        assertEquals(
            "contract-token",
            (AuthCallbackParser.parse(uri) as AuthCallbackResult.WebToken).authToken,
        )
    }
}
