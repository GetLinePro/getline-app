package pro.getline.vpn.getline.auth

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

/**
 * Empty subscriptions must not auto-call dashboard. Trial activation is
 * explicit: GET dashboard after user tap, optional POST, then reread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrialActivationTest {
    @Test
    fun existingSubscriptionNeverTouchesDashboard() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(listOf(item())))
        val repo = GetLineSessionRepository(api, seededStore())

        val subscription = repo.loadPreferredSubscription()

        assertEquals("9", subscription.id)
        assertEquals(1, api.subscriptionCalls)
        assertEquals(0, api.dashboardCalls)
        assertEquals(0, api.activateTrialCalls)
    }

    @Test
    fun emptyListDoesNotCallDashboardWithoutUserTap() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(emptyList()))
        val repo = GetLineSessionRepository(api, seededStore())

        try {
            repo.loadPreferredSubscription()
            fail("expected NoSubscription")
        } catch (_: GetLineAuthException.NoSubscription) {
            // expected
        }
        assertEquals(1, api.subscriptionCalls)
        assertEquals(0, api.dashboardCalls)
        assertEquals(0, api.activateTrialCalls)
    }

    @Test
    fun autoActivatedGetRereadsSubscriptionsWithoutPost() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(listOf(item())),
            autoActivated = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        val ready = result as GetLineSessionRepository.TrialActivationResult.Ready
        assertEquals("9", ready.load.preferred.id)
        assertEquals(1, api.dashboardCalls)
        assertEquals(0, api.activateTrialCalls)
        assertEquals(1, api.subscriptionCalls)
    }

    @Test
    fun availableTrialPostsThenRereads() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(listOf(item())),
            autoActivated = false,
            trialAvailable = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        assertTrue(result is GetLineSessionRepository.TrialActivationResult.Ready)
        assertEquals(1, api.dashboardCalls)
        assertEquals(1, api.activateTrialCalls)
        assertEquals(1, api.subscriptionCalls)
    }

    @Test
    fun unavailableTrialDoesNotPost() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList()),
            autoActivated = false,
            trialAvailable = false,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        val unavailable = result as GetLineSessionRepository.TrialActivationResult.Unavailable
        assertFalse(unavailable.dashboard.trialAvailable)
        assertEquals(1, api.dashboardCalls)
        assertEquals(0, api.activateTrialCalls)
        assertEquals(0, api.subscriptionCalls)
    }

    @Test
    fun paidTrialDoesNotPost() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList()),
            autoActivated = false,
            trialAvailable = true,
            trialPaid = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        assertTrue(result is GetLineSessionRepository.TrialActivationResult.Unavailable)
        assertEquals(0, api.activateTrialCalls)
        assertEquals(0, api.subscriptionCalls)
    }

    @Test
    fun disabledTrialDoesNotPost() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList()),
            autoActivated = false,
            trialAvailable = true,
            trialEnabled = false,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        assertTrue(result is GetLineSessionRepository.TrialActivationResult.Unavailable)
        assertEquals(0, api.activateTrialCalls)
        assertEquals(0, api.subscriptionCalls)
    }

    @Test
    fun recurringOnlyTrialDoesNotPost() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList()),
            autoActivated = false,
            trialAvailable = true,
            trialRecurringOnly = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val result = repo.activateTrialAndLoadPreferred()

        assertTrue(result is GetLineSessionRepository.TrialActivationResult.Unavailable)
        assertEquals(0, api.activateTrialCalls)
        assertEquals(0, api.subscriptionCalls)
    }

    /** Backend accepted POST but reread is still empty — surface NoSubscription. */
    @Test
    fun postThenEmptyRereadThrowsNoSubscription() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList()),
            autoActivated = false,
            trialAvailable = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        try {
            repo.activateTrialAndLoadPreferred()
            fail("expected NoSubscription after empty reread")
        } catch (_: GetLineAuthException.NoSubscription) {
            // expected
        }
        assertEquals(1, api.dashboardCalls)
        assertEquals(1, api.activateTrialCalls)
        assertEquals(1, api.subscriptionCalls)
    }

    @Test
    fun loadPreferredSubscriptionWithList_returnsFullList() = runBlocking {
        val primary = item()
        val secondary = item().copy(
            id = "2",
            isPrimary = false,
            subscriptionLink = environmentLink() + "/2",
        )
        val api = FakeApi(itemsPerCall = listOf(listOf(secondary, primary)))
        val repo = GetLineSessionRepository(api, seededStore())

        val load = repo.loadPreferredSubscriptionWithList()

        assertEquals("9", load.preferred.id)
        assertEquals(2, load.all.size)
        assertEquals(1, api.subscriptionCalls)
        assertEquals(0, api.dashboardCalls)
    }

    @Test
    fun dashboardFailureSurfacesToCaller() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(emptyList()), failDashboard = true)
        val repo = GetLineSessionRepository(api, seededStore())

        try {
            repo.activateTrialAndLoadPreferred()
            fail("expected HttpFailure")
        } catch (e: GetLineAuthException.HttpFailure) {
            assertEquals(500, e.code)
        }
        assertEquals(1, api.dashboardCalls)
        assertEquals(0, api.activateTrialCalls)
    }

    private fun environmentLink(): String {
        return if (pro.getline.vpn.GetLineControlPlaneHostPolicy.isE2e) {
            "https://app.stage.getline.pro/sub/e2e"
        } else {
            "https://app.getline.pro/sub/user"
        }
    }

    private fun item(): SubscriptionItem {
        return SubscriptionItem(
            id = "9",
            name = null,
            planName = "P",
            planType = null,
            kind = "trial",
            isPrimary = true,
            isActive = true,
            expireAtEpochMillis = null,
            daysLeft = 3,
            deviceLimit = null,
            totalDeviceLimit = null,
            devicesCount = null,
            traffic = null,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = environmentLink(),
        )
    }

    private fun seededStore(): GetLineSessionStore {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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

    private class FakeApi(
        private val itemsPerCall: List<List<SubscriptionItem>>,
        private val autoActivated: Boolean = false,
        private val trialAvailable: Boolean = false,
        private val trialEnabled: Boolean = true,
        private val trialPaid: Boolean = false,
        private val trialRecurringOnly: Boolean = false,
        private val failDashboard: Boolean = false,
    ) : GetLineAuthApi {
        var subscriptionCalls = 0
        var dashboardCalls = 0
        var activateTrialCalls = 0

        override suspend fun startBrowserAuth(
            method: AuthMethod,
            codeChallenge: String,
            appRedirect: String,
        ) = error("not used")

        override suspend fun exchangeNativeCode(
            code: String,
            codeVerifier: String,
        ) = error("not used")

        override suspend fun sendEmailOtp(email: String) = error("not used")
        override suspend fun verifyEmailOtp(email: String, code: String) = error("not used")
        override suspend fun getCurrentUser(webToken: String) = error("not used")
        override suspend fun generateDeviceKey(webToken: String) = error("not used")
        override suspend fun exchangeDeviceKey(deviceKey: String) = error("not used")
        override suspend fun refresh(refreshToken: String) = error("not used")

        override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
            val index = minOf(subscriptionCalls, itemsPerCall.lastIndex)
            subscriptionCalls++
            return SubscriptionsResponse(false, itemsPerCall[index])
        }

        override suspend fun getDashboard(accessToken: String): DashboardInfo {
            dashboardCalls++
            if (failDashboard) {
                throw GetLineAuthException.HttpFailure(500, "HTTP 500")
            }
            return DashboardInfo(
                trialEnabled = trialEnabled,
                trialAvailable = trialAvailable,
                trialAutoActivated = autoActivated,
                trialDays = 3,
                trialPaid = trialPaid,
                trialRecurringOnly = trialRecurringOnly,
            )
        }

        override suspend fun activateTrial(accessToken: String) {
            activateTrialCalls++
        }
    }
}
