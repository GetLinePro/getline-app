package pro.getline.vpn.getline.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionLoadRepositoryTest {
    @Test
    fun loadSubscriptionForUi_noSession_signedOut() = runBlocking {
        val api = FakeAuthApi()
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.SignedOut, result)
        assertEquals(0, api.subscriptionsCalls)
    }

    @Test
    fun loadSubscriptionForUi_emptyList_successNullPreferred() = runBlocking {
        val api = FakeAuthApi(
            subscriptions = SubscriptionsResponse(false, emptyList()),
        )
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertTrue(result is SubscriptionLoadResult.Success)
        assertEquals(null, (result as SubscriptionLoadResult.Success).preferred)
    }

    @Test
    fun loadSubscriptionForUi_selectsPreferred() = runBlocking {
        val primary = item(id = "1", primary = true, link = "https://a")
        val other = item(id = "2", primary = false, link = "https://b")
        val api = FakeAuthApi(
            subscriptions = SubscriptionsResponse(false, listOf(other, primary)),
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.loadSubscriptionForUi() as SubscriptionLoadResult.Success
        assertEquals("1", result.preferred?.id)
    }

    @Test
    fun getSubscriptionsAuthenticated_401_refreshesOnceAndRetries() = runBlocking {
        val api = FakeAuthApi(failSubscriptionsTimes = 1)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val response = repo.getSubscriptionsAuthenticated()
        assertEquals(1, response.subscriptions.size)
        assertEquals(2, api.subscriptionsCalls)
        assertEquals(1, api.refreshCalls)
    }

    @Test
    fun getSubscriptionsAuthenticated_failedRecovery_logsOutAndThrows() = runBlocking {
        val api = FakeAuthApi(failSubscriptionsTimes = 2, failRefresh = true)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        try {
            repo.getSubscriptionsAuthenticated()
            fail("expected failure")
        } catch (_: GetLineAuthException) {
            // expected
        }
        // refresh fails before retry completes; or second 401 after refresh
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun loadSubscriptionForUi_failedRecovery_signedOut() = runBlocking {
        val api = FakeAuthApi(failSubscriptionsTimes = 99, failRefresh = true)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.SignedOut, result)
        assertFalse(repo.hasSession())
    }

    @Test
    fun loadSubscriptionForUi_protocolRefreshFailure_signedOutNotTransient() = runBlocking {
        // Non-HTTP recovery failure (malformed refresh body) must clear session and
        // surface SignedOut — not Failed/Transient while hasSession() is false.
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshError = GetLineAuthException.Protocol("malformed refresh"),
        )
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.SignedOut, result)
        assertFalse(repo.hasSession())
    }

    @Test
    fun getSubscriptionsAuthenticated_cancelDuringRefresh_preservesSession() = runBlocking {
        val enteredRefresh = CompletableDeferred<Unit>()
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshBlock = {
                enteredRefresh.complete(Unit)
                awaitCancellation()
            },
        )
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val job = launch {
            try {
                repo.getSubscriptionsAuthenticated()
                fail("expected cancellation")
            } catch (_: CancellationException) {
                // expected
            }
        }
        enteredRefresh.await()
        job.cancelAndJoin()

        assertTrue(
            "Cancellation must not clear the native session",
            store.hasRefreshToken(),
        )
    }

    @Test
    fun loadSubscriptionForUi_cancelDuringRefresh_rethrowsAndKeepsSession() = runBlocking {
        val enteredRefresh = CompletableDeferred<Unit>()
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshBlock = {
                enteredRefresh.complete(Unit)
                awaitCancellation()
            },
        )
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val deferred = async {
            repo.loadSubscriptionForUi()
        }
        enteredRefresh.await()
        deferred.cancel()
        try {
            deferred.await()
            fail("expected CancellationException")
        } catch (_: CancellationException) {
            // expected — not mapped to TransientFailure / SignedOut
        }
        assertTrue(store.hasRefreshToken())
    }

    @Test
    fun loadSubscriptionForUi_serverError_transient() = runBlocking {
        val api = FakeAuthApi(subscriptionsHttpCode = 500)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
        assertEquals(1, api.subscriptionsCalls)
    }

    @Test
    fun loadSubscriptionForUi_403_keepsSession_transient() = runBlocking {
        val api = FakeAuthApi(subscriptionsHttpCode = 403)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
    }

    @Test
    fun getSubscriptionsAuthenticated_second401_logsOut_second403_doesNot() = runBlocking {
        // After one 401 on subscriptions, refresh succeeds; second call returns 403.
        val api = FakeAuthApi(failSubscriptionsTimes = 1, secondSubscriptionsHttpCode = 403)
        val store = seededStore()
        val repo = GetLineSessionRepository(api, store)

        try {
            repo.getSubscriptionsAuthenticated()
            fail("expected 403")
        } catch (e: GetLineAuthException.HttpFailure) {
            assertEquals(403, e.code)
        }
        assertTrue(
            "403 after successful refresh must not clear the session",
            store.hasRefreshToken(),
        )
    }

    private fun seededStore(): GetLineSessionStore {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400L,
            ),
        )
        return store
    }

    private fun item(
        id: String,
        primary: Boolean,
        link: String?,
    ): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            name = null,
            planName = "P",
            planType = null,
            kind = null,
            isPrimary = primary,
            isActive = true,
            expireAtEpochMillis = null,
            daysLeft = null,
            deviceLimit = null,
            totalDeviceLimit = null,
            devicesCount = null,
            traffic = null,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = link,
        )
    }

    private class FakeAuthApi(
        private val subscriptions: SubscriptionsResponse = SubscriptionsResponse(
            false,
            listOf(
                SubscriptionItem(
                    id = "1",
                    name = null,
                    planName = "Trial",
                    planType = null,
                    kind = "trial",
                    isPrimary = true,
                    isActive = true,
                    expireAtEpochMillis = null,
                    daysLeft = null,
                    deviceLimit = 3,
                    totalDeviceLimit = 3,
                    devicesCount = -1,
                    traffic = null,
                    autopayEnabled = false,
                    renewalDisabled = false,
                    planArchived = false,
                    subscriptionLink = "https://example.test/sub",
                ),
            ),
        ),
        private var failSubscriptionsTimes: Int = 0,
        private val failRefresh: Boolean = false,
        private val refreshError: GetLineAuthException? = null,
        private val refreshBlock: (suspend () -> NativeSession)? = null,
        private val subscriptionsHttpCode: Int? = null,
        /** Applied after a successful refresh when the first subscription call 401'd. */
        private val secondSubscriptionsHttpCode: Int? = null,
    ) : GetLineAuthApi {
        var subscriptionsCalls = 0
        var refreshCalls = 0

        override suspend fun startBrowserAuth(method: AuthMethod) =
            error("not used")

        override suspend fun sendEmailOtp(email: String) = error("not used")
        override suspend fun verifyEmailOtp(email: String, code: String) =
            error("not used")

        override suspend fun getCurrentUser(webToken: String) = error("not used")
        override suspend fun generateDeviceKey(webToken: String) = error("not used")
        override suspend fun exchangeDeviceKey(deviceKey: String) = error("not used")

        override suspend fun refresh(refreshToken: String): NativeSession {
            refreshCalls++
            if (refreshBlock != null) {
                return refreshBlock.invoke()
            }
            if (refreshError != null) {
                throw refreshError
            }
            if (failRefresh) {
                throw GetLineAuthException.HttpFailure(401, "refresh failed")
            }
            return NativeSession(
                accessToken = "access-refreshed",
                refreshToken = "refresh-refreshed",
                expiresInSeconds = 86_400L,
            )
        }

        override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
            subscriptionsCalls++
            if (failSubscriptionsTimes > 0) {
                failSubscriptionsTimes--
                throw GetLineAuthException.HttpFailure(401, "HTTP 401")
            }
            if (secondSubscriptionsHttpCode != null && subscriptionsCalls >= 2) {
                throw GetLineAuthException.HttpFailure(
                    secondSubscriptionsHttpCode,
                    "HTTP $secondSubscriptionsHttpCode",
                )
            }
            if (subscriptionsHttpCode != null) {
                throw GetLineAuthException.HttpFailure(
                    subscriptionsHttpCode,
                    "HTTP $subscriptionsHttpCode",
                )
            }
            return subscriptions
        }
    }
}
