package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GetLineAuthErrorClassifierTest {
    @Test
    fun nativeRefresh_preservesHttpStatusInsteadOfBodyClassification() {
        val expired = GetLineAuthErrorClassifier.classify(
            401,
            "refresh token expired",
            AuthErrorContext.NativeRefresh,
        )
        assertTrue(expired is GetLineAuthException.HttpFailure)
        assertEquals(401, (expired as GetLineAuthException.HttpFailure).code)

        val rateLimited = GetLineAuthErrorClassifier.classify(
            429,
            "too many attempts",
            AuthErrorContext.NativeRefresh,
        )
        assertTrue(rateLimited is GetLineAuthException.HttpFailure)
        assertEquals(429, (rateLimited as GetLineAuthException.HttpFailure).code)
    }

    @Test
    fun rateLimited_on429() {
        val e = GetLineAuthErrorClassifier.classify(429, "slow down")
        assertTrue(e is GetLineAuthException.RateLimited)
        assertEquals("slow down", e.message)
    }

    @Test
    fun noAccount_substring() {
        val e = GetLineAuthErrorClassifier.classify(400, "no_account for this login")
        assertTrue(e is GetLineAuthException.NoAccount)
    }

    @Test
    fun emailDomainNotAllowed() {
        val e = GetLineAuthErrorClassifier.classify(422, "email_domain_not_allowed")
        assertTrue(e is GetLineAuthException.EmailDomainNotAllowed)
    }

    @Test
    fun otpExpired() {
        val e = GetLineAuthErrorClassifier.classify(400, "code expired")
        assertTrue(e is GetLineAuthException.OtpExpired)
    }

    @Test
    fun tooMany_isRateLimited() {
        val e = GetLineAuthErrorClassifier.classify(400, "too many attempts")
        assertTrue(e is GetLineAuthException.RateLimited)
    }

    @Test
    fun invalidOtp_residualOnlyOnVerifyContext() {
        val e = GetLineAuthErrorClassifier.classify(
            statusCode = 401,
            body = "no otp code found for this email",
            context = AuthErrorContext.EmailOtpVerify,
        )
        assertTrue(e is GetLineAuthException.InvalidOtp)
    }

    @Test
    fun invalidEmailAddress_isNotInvalidOtpOnDefaultContext() {
        val e = GetLineAuthErrorClassifier.classify(400, "Invalid email address")
        assertTrue(e is GetLineAuthException.HttpFailure)
        assertEquals(400, (e as GetLineAuthException.HttpFailure).code)
        assertEquals("Invalid email address", e.message)
    }

    @Test
    fun verifyContext_unknownBody_isInvalidOtp() {
        val e = GetLineAuthErrorClassifier.classify(
            statusCode = 401,
            body = "something unexpected",
            context = AuthErrorContext.EmailOtpVerify,
        )
        assertTrue(e is GetLineAuthException.InvalidOtp)
    }

    @Test
    fun verifyContext_stillMapsNoAccount() {
        val e = GetLineAuthErrorClassifier.classify(
            statusCode = 400,
            body = "no_account",
            context = AuthErrorContext.EmailOtpVerify,
        )
        assertTrue(e is GetLineAuthException.NoAccount)
    }

    @Test
    fun verifyContext_serverOutage_isHttpFailureNotInvalidOtp() {
        for (code in listOf(500, 502, 503)) {
            val e = GetLineAuthErrorClassifier.classify(
                statusCode = code,
                body = "Bad Gateway",
                context = AuthErrorContext.EmailOtpVerify,
            )
            assertTrue("$code should stay HttpFailure", e is GetLineAuthException.HttpFailure)
            assertEquals(code, (e as GetLineAuthException.HttpFailure).code)
        }
    }

    @Test
    fun serverOutage_bodyKeywordsDoNotOverrideStatus() {
        // A 5xx body is untrusted: gateway pages carrying "expired" or "no_account"
        // must not be presented as a user-fixable auth error.
        for (body in listOf("code expired", "no_account", "too many")) {
            val e = GetLineAuthErrorClassifier.classify(
                statusCode = 503,
                body = body,
                context = AuthErrorContext.EmailOtpVerify,
            )
            assertTrue("\"$body\" on 503 should stay HttpFailure", e is GetLineAuthException.HttpFailure)
        }
    }

    @Test
    fun genericHttpFailure() {
        val e = GetLineAuthErrorClassifier.classify(500, "boom")
        assertTrue(e is GetLineAuthException.HttpFailure)
        assertEquals(500, (e as GetLineAuthException.HttpFailure).code)
        assertEquals("boom", e.message)
    }

    @Test
    fun emptyBody_usesStatusFallback() {
        val e = GetLineAuthErrorClassifier.classify(503, "  ")
        assertTrue(e is GetLineAuthException.HttpFailure)
        assertEquals("HTTP 503", e.message)
    }
}
