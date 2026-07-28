package pro.getline.vpn.getline.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EmailOtpAuthTest {
    @Test
    fun verifyBody_alwaysIncludesIntentLogin() {
        val body = RwpGetLineAuthApi.emailOtpVerifyBody(
            email = "user@example.com",
            code = "123456",
        )
        val json = JSONObject(body)
        assertEquals("user@example.com", json.getString("email"))
        assertEquals("123456", json.getString("code"))
        assertEquals("login", json.getString("intent"))
        assertEquals(3, json.length())
    }

    @Test
    fun sendBody_containsEmailOnly() {
        val body = RwpGetLineAuthApi.emailOtpSendBody("user@example.com")
        val json = JSONObject(body)
        assertEquals("user@example.com", json.getString("email"))
        assertEquals(1, json.length())
    }

    @Test
    fun parseVerify_returnsWebTokenFromTokenField() {
        val json = JSONObject(
            """
            {
              "token": "web-token-abc",
              "expires_in": 300,
              "user": {
                "customer_id": "c1",
                "email": "user@example.com",
                "telegram_id": 0
              },
              "sent": true
            }
            """.trimIndent(),
        )
        val result = RwpGetLineAuthApi.parseEmailOtpVerifyResult(json)
        assertEquals("web-token-abc", result.webToken)
        assertEquals(300L, result.expiresInSeconds)
    }

    @Test
    fun parseVerify_missingToken_isProtocolError() {
        val json = JSONObject("""{"expires_in": 300}""")
        try {
            RwpGetLineAuthApi.parseEmailOtpVerifyResult(json)
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun parseVerify_missingExpiresIn_isProtocolError() {
        val json = JSONObject("""{"token": "t"}""")
        try {
            RwpGetLineAuthApi.parseEmailOtpVerifyResult(json)
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun parseSend_optionalExpiresIn() {
        val withTtl = RwpGetLineAuthApi.parseEmailOtpSendResult(
            JSONObject("""{"expires_in": 300, "sent": true}"""),
        )
        assertEquals(300L, withTtl.expiresInSeconds)

        val empty = RwpGetLineAuthApi.parseEmailOtpSendResult(JSONObject())
        assertNull(empty.expiresInSeconds)
    }

    @Test
    fun emailPaths_matchRwpContract() {
        assertEquals("/api/auth/email/send-otp", RwpGetLineAuthApi.EMAIL_SEND_OTP_PATH)
        assertEquals("/api/auth/email/verify-otp", RwpGetLineAuthApi.EMAIL_VERIFY_OTP_PATH)
    }

    @Test
    fun authMethod_emailDoesNotRequireBrowser() {
        assertTrue(AuthMethod.Telegram.requiresBrowser())
        assertTrue(AuthMethod.Google.requiresBrowser())
        assertTrue(!AuthMethod.Email.requiresBrowser())
    }
}
