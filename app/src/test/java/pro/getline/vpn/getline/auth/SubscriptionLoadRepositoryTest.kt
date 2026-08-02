package pro.getline.vpn.getline.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionLoadRepositoryTest {
    @Test
    fun loadPreferredSubscription_rejectsWrongEnvironmentLink() = runBlocking {
        val foreignLink = if (pro.getline.vpn.GetLineControlPlaneHostPolicy.isE2e) {
            "https://app.getline.pro/sub/real"
        } else {
            "https://app.stage.getline.pro/sub/e2e"
        }
        val api = FakeAuthApi(
            subscriptions = SubscriptionsResponse(
                false,
                listOf(item(id = "1", primary = true, link = foreignLink)),
            ),
        )
        val repo = GetLineSessionRepository(api, seededStore())
        try {
            repo.loadPreferredSubscription()
            fail("must reject foreign-environment subscription_link before import")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
    }

    @Test
    fun loadPreferredSubscription_acceptsEnvironmentLink() = runBlocking {
        val okLink = if (pro.getline.vpn.GetLineControlPlaneHostPolicy.isE2e) {
            "https://app.stage.getline.pro/sub/e2e"
        } else {
            "https://app.getline.pro/sub/user"
        }
        val api = FakeAuthApi(
            subscriptions = SubscriptionsResponse(
                false,
                listOf(item(id = "9", primary = true, link = okLink)),
            ),
        )
        val repo = GetLineSessionRepository(api, seededStore())
        val item = repo.loadPreferredSubscription()
        assertEquals("9", item.id)
        assertEquals(okLink, item.subscriptionLink)
    }

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
    fun getSubscriptionsAuthenticated_concurrent401_rotatesSessionOnce() = runBlocking {
        val enteredRefresh = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val api = FakeAuthApi(
            failSubscriptionsTimes = 2,
            refreshBlock = {
                enteredRefresh.complete(Unit)
                releaseRefresh.await()
                NativeSession(
                    accessToken = "access-refreshed",
                    refreshToken = "refresh-refreshed",
                    expiresInSeconds = 86_400L,
                )
            },
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            repo.getSubscriptionsAuthenticated()
        }
        enteredRefresh.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repo.getSubscriptionsAuthenticated()
        }
        releaseRefresh.complete(Unit)

        assertEquals(1, first.await().subscriptions.size)
        assertEquals(1, second.await().subscriptions.size)
        assertEquals(1, api.refreshCalls)
        assertEquals(4, api.subscriptionsCalls)
    }

    @Test
    fun loadSubscriptionForUi_expiredAccess_refresh401OrInvalidGrant_rejectsAccountSession() =
        runBlocking {
            for (error in listOf(
                GetLineAuthException.HttpFailure(401, "refresh rejected"),
                GetLineAuthException.HttpFailure(400, "{\"error\":\"invalid_grant\"}"),
            )) {
                val api = FakeAuthApi(
                    refreshError = error,
                )
                val store = seededManagedStore().also {
                    it.accessTokenExpiresAtEpochMs = 0L
                }
                val repo = GetLineSessionRepository(api, store)

                val result = repo.loadSubscriptionForUi()
                assertEquals(SubscriptionLoadResult.SignedOut, result)
                assertFalse(store.hasRefreshToken())
                assertEquals("managed-uuid", store.managedProfileUuid)
                assertEquals("subscription-id", store.subscriptionId)
                assertEquals(null, store.managedProfileSource)
                assertEquals(null, store.customerId)
                assertFalse(repo.canRemoteRepair())
                assertEquals(1, api.refreshCalls)
                assertEquals(0, api.subscriptionsCalls)
            }
        }

    @Test
    fun loadSubscriptionForUi_expiredAccess_unknown400Or403_isTransient() = runBlocking {
        for (code in listOf(400, 403)) {
            val api = FakeAuthApi(
                refreshError = GetLineAuthException.HttpFailure(code, "edge rejected request"),
            )
            val store = seededManagedStore().also {
                it.accessTokenExpiresAtEpochMs = 0L
            }
            val repo = GetLineSessionRepository(api, store)

            val result = repo.loadSubscriptionForUi()
            assertEquals(SubscriptionLoadResult.TransientFailure, result)
            assertTrue(repo.hasSession())
            assertEquals("refresh", store.refreshToken)
            assertEquals("managed-uuid", store.managedProfileUuid)
            assertEquals("https://example.test/managed", store.managedProfileSource)
            assertEquals("subscription-id", store.subscriptionId)
            assertEquals("customer-id", store.customerId)
            assertTrue(repo.canRemoteRepair())
            assertEquals(1, api.refreshCalls)
            assertEquals(0, api.subscriptionsCalls)
        }
    }

    @Test
    fun loadSubscriptionForUi_expiredAccess_refresh5xx_isTransient() = runBlocking {
        val api = FakeAuthApi(
            refreshError = GetLineAuthException.HttpFailure(503, "refresh unavailable"),
        )
        val store = seededManagedStore().also {
            it.accessTokenExpiresAtEpochMs = 0L
        }
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
        assertEquals("refresh", store.refreshToken)
        assertEquals("managed-uuid", store.managedProfileUuid)
        assertEquals("https://example.test/managed", store.managedProfileSource)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals("customer-id", store.customerId)
        assertEquals(1, api.refreshCalls)
        assertEquals(0, api.subscriptionsCalls)
    }

    @Test
    fun loadSubscriptionForUi_refresh5xx_transientAndPreservesState() = runBlocking {
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshError = GetLineAuthException.HttpFailure(503, "refresh unavailable"),
        )
        val store = seededManagedStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
        assertEquals("refresh", store.refreshToken)
        assertEquals("managed-uuid", store.managedProfileUuid)
        assertEquals("https://example.test/managed", store.managedProfileSource)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals("customer-id", store.customerId)
    }

    @Test
    fun loadSubscriptionForUi_networkRefreshFailure_transientAndPreservesState() = runBlocking {
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshError = IOException("offline"),
        )
        val store = seededManagedStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
        assertEquals("refresh", store.refreshToken)
        assertEquals("managed-uuid", store.managedProfileUuid)
        assertEquals("https://example.test/managed", store.managedProfileSource)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals("customer-id", store.customerId)
    }

    @Test
    fun loadSubscriptionForUi_protocolRefreshFailure_transientAndPreservesState() = runBlocking {
        val api = FakeAuthApi(
            failSubscriptionsTimes = 1,
            refreshError = GetLineAuthException.Protocol("malformed refresh"),
        )
        val store = seededManagedStore()
        val repo = GetLineSessionRepository(api, store)

        val result = repo.loadSubscriptionForUi()
        assertEquals(SubscriptionLoadResult.TransientFailure, result)
        assertTrue(repo.hasSession())
        assertEquals("managed-uuid", store.managedProfileUuid)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals("customer-id", store.customerId)
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
    fun getSubscriptionsAuthenticated_second401_clearsSessionButKeepsBinding() = runBlocking {
        val api = FakeAuthApi(failSubscriptionsTimes = 2)
        val store = seededManagedStore()
        val repo = GetLineSessionRepository(api, store)

        try {
            repo.getSubscriptionsAuthenticated()
            fail("expected 401")
        } catch (e: GetLineAuthException.HttpFailure) {
            assertEquals(401, e.code)
        }
        assertFalse(store.hasRefreshToken())
        assertEquals("managed-uuid", store.managedProfileUuid)
        assertEquals(null, store.managedProfileSource)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals(null, store.customerId)
        assertFalse(repo.canRemoteRepair())
    }

    @Test
    fun getSubscriptionsAuthenticated_second403_keepsSession() = runBlocking {
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

    private fun seededManagedStore(): GetLineSessionStore {
        return seededStore().also { store ->
            store.managedProfileUuid = "managed-uuid"
            store.managedProfileSource = "https://example.test/managed"
            store.subscriptionId = "subscription-id"
            store.customerId = "customer-id"
        }
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
        private val refreshError: Exception? = null,
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

        // Trial provisioning belongs to login, not to the subscription UI path.
        override suspend fun getDashboard(accessToken: String) = error("not used")

        override suspend fun refresh(refreshToken: String): NativeSession {
            refreshCalls++
            if (refreshBlock != null) {
                return refreshBlock.invoke()
            }
            if (refreshError != null) {
                throw refreshError
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
