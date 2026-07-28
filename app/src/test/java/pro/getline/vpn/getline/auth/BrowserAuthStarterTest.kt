package pro.getline.vpn.getline.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthStarterTest {
    @Test
    fun google_usesStartEndpointAuthUrlUnchanged() = runBlocking {
        val expected =
            "https://accounts.google.com/o/oauth2/auth?client_id=x&state=y"
        val api = FakeAuthApi(googleAuthUrl = expected)
        val starter = BrowserAuthStarter(api)

        val url = starter.resolveAuthUrl(AuthMethod.Google)

        assertEquals(expected, url)
        assertEquals(listOf(AuthMethod.Google), api.startCalls)
    }

    @Test
    fun telegram_usesTrampolineWithoutCallingStartApi() = runBlocking {
        val api = FakeAuthApi()
        val starter = BrowserAuthStarter(api)

        val url = starter.resolveAuthUrl(AuthMethod.Telegram)

        assertEquals(BrowserAuthLauncher.TELEGRAM_TRAMPOLINE_URL, url)
        assertTrue(api.startCalls.isEmpty())
    }

    @Test
    fun email_cannotStartBrowserPath() = runBlocking {
        val api = FakeAuthApi()
        val starter = BrowserAuthStarter(api)
        try {
            starter.resolveAuthUrl(AuthMethod.Email)
            fail("expected rejection for Email")
        } catch (_: IllegalArgumentException) {
            // require(method.requiresBrowser())
        }
        assertTrue(api.startCalls.isEmpty())
        assertFalse(AuthMethod.Email.requiresBrowser())
    }

    @Test
    fun google_missingAuthUrl_isProtocolError() = runBlocking {
        val api = FakeAuthApi(googleAuthUrl = null, missingAuthUrl = true)
        val starter = BrowserAuthStarter(api)
        try {
            starter.resolveAuthUrl(AuthMethod.Google)
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun google_blankAuthUrl_isProtocolError() = runBlocking {
        val api = FakeAuthApi(googleAuthUrl = "   ")
        val starter = BrowserAuthStarter(api)
        try {
            starter.resolveAuthUrl(AuthMethod.Google)
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun google_malformedAuthUrl_doesNotPassValidation() = runBlocking {
        val api = FakeAuthApi(googleAuthUrl = "not-a-url")
        val starter = BrowserAuthStarter(api)
        try {
            starter.resolveAuthUrl(AuthMethod.Google)
            fail("expected Protocol")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

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

    private class FakeAuthApi(
        private val googleAuthUrl: String? = null,
        private val missingAuthUrl: Boolean = false,
    ) : GetLineAuthApi {
        val startCalls = mutableListOf<AuthMethod>()

        override suspend fun startBrowserAuth(
            method: AuthMethod,
        ): BrowserAuthStartResponse {
            startCalls += method
            if (method != AuthMethod.Google) {
                throw AssertionError("Unexpected method $method")
            }
            if (missingAuthUrl) {
                throw GetLineAuthException.Protocol("auth_url missing")
            }
            val url = googleAuthUrl
                ?: throw GetLineAuthException.Protocol("auth_url missing")
            if (url.isBlank()) {
                throw GetLineAuthException.Protocol("auth_url is blank")
            }
            return BrowserAuthStartResponse(authUrl = url)
        }

        override suspend fun sendEmailOtp(email: String): EmailOtpSendResult {
            throw UnsupportedOperationException()
        }

        override suspend fun verifyEmailOtp(
            email: String,
            code: String,
        ): EmailOtpVerifyResult {
            throw UnsupportedOperationException()
        }

        override suspend fun getCurrentUser(webToken: String): CurrentUser {
            throw UnsupportedOperationException()
        }

        override suspend fun generateDeviceKey(webToken: String): DeviceKey {
            throw UnsupportedOperationException()
        }

        override suspend fun exchangeDeviceKey(deviceKey: String): NativeSession {
            throw UnsupportedOperationException()
        }

        override suspend fun refresh(refreshToken: String): NativeSession {
            throw UnsupportedOperationException()
        }

        override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
            throw UnsupportedOperationException()
        }
    }
}
