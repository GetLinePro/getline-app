package pro.getline.vpn.getline.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Verifies that web-token handoff is provider-agnostic: once a web auth token
 * is available, device-key generate/exchange runs without a provider field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthHandoffTest {
    @Test
    fun establishFromWebToken_usesDeviceKeyFlowOnce() = runBlocking {
        val api = RecordingAuthApi()
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        val repo = GetLineSessionRepository(api, store)

        val session = repo.establishFromWebToken("web-token-from-google-or-telegram")

        // Trial provisioning is not part of establish: it costs a request that
        // only an account without a subscription needs. See TrialProvisioningTest.
        assertEquals(listOf("generate", "exchange"), api.calls)
        assertEquals("native-access", session.accessToken)
        assertEquals("native-refresh", session.refreshToken)
        assertEquals("native-access", store.accessToken)
        assertEquals("native-refresh", store.refreshToken)
        assertEquals("web-token-from-google-or-telegram", api.lastGenerateBearer)
        assertEquals("one-time-key", api.lastExchangeDeviceKey)
    }

    @Test
    fun establishFromWebToken_doesNotPersistOnGenerateFailure() = runBlocking {
        val api = RecordingAuthApi(failGenerate = true)
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        val repo = GetLineSessionRepository(api, store)

        try {
            repo.establishFromWebToken("web-token")
            fail("expected failure")
        } catch (_: GetLineAuthException.HttpFailure) {
            // expected
        }

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals(listOf("generate"), api.calls)
    }

    private class RecordingAuthApi(
        private val failGenerate: Boolean = false,
    ) : GetLineAuthApi {
        val calls = mutableListOf<String>()
        var lastGenerateBearer: String? = null
        var lastExchangeDeviceKey: String? = null

        override suspend fun startBrowserAuth(
            method: AuthMethod,
        ): BrowserAuthStartResponse {
            throw UnsupportedOperationException()
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
            return CurrentUser(null, null, null, null, null)
        }

        override suspend fun generateDeviceKey(webToken: String): DeviceKey {
            calls += "generate"
            lastGenerateBearer = webToken
            if (failGenerate) {
                throw GetLineAuthException.HttpFailure(401, "HTTP 401")
            }
            return DeviceKey("one-time-key")
        }

        override suspend fun exchangeDeviceKey(deviceKey: String): NativeSession {
            calls += "exchange"
            lastExchangeDeviceKey = deviceKey
            return NativeSession(
                accessToken = "native-access",
                refreshToken = "native-refresh",
                expiresInSeconds = 3600L,
            )
        }

        override suspend fun refresh(refreshToken: String): NativeSession {
            throw UnsupportedOperationException()
        }

        override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun getDashboard(accessToken: String): DashboardInfo {
            calls += "dashboard"
            throw UnsupportedOperationException("login must not call dashboard")
        }

        override suspend fun activateTrial(accessToken: String) {
            calls += "activate_trial"
            throw UnsupportedOperationException("login must not activate trial")
        }
    }
}
