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
 * Web-token (email) and native-code establish paths.
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

        val session = repo.establishFromWebToken("web-token-from-email")

        assertEquals(listOf("generate", "exchange"), api.calls)
        assertEquals("native-access", session.accessToken)
        assertEquals("native-refresh", session.refreshToken)
        assertEquals("native-access", store.accessToken)
        assertEquals("native-refresh", store.refreshToken)
        assertEquals("web-token-from-email", api.lastGenerateBearer)
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

    @Test
    fun establishFromNativeCode_exchangesAndPersists() = runBlocking {
        val api = RecordingAuthApi()
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        val repo = GetLineSessionRepository(api, store)

        val session = repo.establishFromNativeCode("one-time-code", "verifier")

        assertEquals(listOf("native_exchange"), api.calls)
        assertEquals("one-time-code", api.lastNativeCode)
        assertEquals("verifier", api.lastNativeVerifier)
        assertEquals("native-access", session.accessToken)
        assertEquals("native-access", store.accessToken)
        assertEquals("native-refresh", store.refreshToken)
    }

    @Test
    fun establishFromNativeCode_doesNotPersistOnExchangeFailure() = runBlocking {
        val api = RecordingAuthApi(failNativeExchange = true)
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        val repo = GetLineSessionRepository(api, store)

        try {
            repo.establishFromNativeCode("code", "verifier")
            fail("expected failure")
        } catch (_: GetLineAuthException.HttpFailure) {
            // expected
        }

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals(listOf("native_exchange"), api.calls)
    }

    private class RecordingAuthApi(
        private val failGenerate: Boolean = false,
        private val failNativeExchange: Boolean = false,
    ) : GetLineAuthApi {
        val calls = mutableListOf<String>()
        var lastGenerateBearer: String? = null
        var lastExchangeDeviceKey: String? = null
        var lastNativeCode: String? = null
        var lastNativeVerifier: String? = null

        override suspend fun startBrowserAuth(
            method: AuthMethod,
            codeChallenge: String,
            appRedirect: String,
        ): BrowserAuthStartResponse {
            throw UnsupportedOperationException()
        }

        override suspend fun exchangeNativeCode(
            code: String,
            codeVerifier: String,
        ): NativeSession {
            calls += "native_exchange"
            lastNativeCode = code
            lastNativeVerifier = codeVerifier
            if (failNativeExchange) {
                throw GetLineAuthException.HttpFailure(400, "invalid_grant")
            }
            return NativeSession(
                accessToken = "native-access",
                refreshToken = "native-refresh",
                expiresInSeconds = 3600L,
            )
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
