package pro.getline.vpn.getline.auth

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun parse_authToken_success() {
        val uri = callback("auth_token=web-token-value&expires_in=86400")
        val result = AuthCallbackParser.parse(uri)
        assertEquals("web-token-value", result.authToken)
        assertEquals(86400L, result.expiresInSeconds)
    }

    @Test
    fun parse_missingExpiresIn_stillSucceeds() {
        val uri = callback("auth_token=only-token")
        val result = AuthCallbackParser.parse(uri)
        assertEquals("only-token", result.authToken)
        assertNull(result.expiresInSeconds)
    }

    @Test
    fun parse_authError_isInvalidCallback() {
        val uri = callback("auth_error=access_denied")
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (e: GetLineAuthException.InvalidCallback) {
            assertTrue(e.message!!.contains("error", ignoreCase = true))
            assertTrue(!e.message!!.contains("access_denied"))
        }
    }

    @Test
    fun parse_missingResultFields_rejected() {
        val uri = callback("foo=bar")
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_unexpectedHost_rejected() {
        val uri = Uri.parse(
            "https://evil.example/#/login?auth_token=token",
        )
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_wrongEnvironmentHost_rejected() {
        // Production callback host on e2e build (and vice versa).
        val foreign = if (AppEnvironment.callbackHost == "auth.stage.getline.pro") {
            "app.getline.pro"
        } else {
            "auth.stage.getline.pro"
        }
        val uri = Uri.parse(
            "https://$foreign/#/login?auth_token=token",
        )
        try {
            AuthCallbackParser.parse(uri)
            fail("expected InvalidCallback for wrong-environment host")
        } catch (_: GetLineAuthException.InvalidCallback) {
            // expected
        }
    }

    @Test
    fun parse_unrelatedRoute_rejected() {
        val uri = Uri.parse(
            "https://${AppEnvironment.callbackHost}/#/settings?auth_token=token",
        )
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
    fun parseWebAuthToken_matchesParse() {
        val uri = callback("auth_token=abc&expires_in=1")
        assertEquals("abc", AuthCallbackParser.parseWebAuthToken(uri))
    }

    private fun callback(fragmentQuery: String): Uri {
        return Uri.parse(
            "https://${AppEnvironment.callbackHost}/#/login?$fragmentQuery",
        )
    }
}
