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
 * GET /api/dashboard provisions the trial on accounts that have none, so it is
 * called only after the subscription list comes back empty — never for users who
 * already have something to import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrialProvisioningTest {
    @Test
    fun existingSubscriptionSkipsDashboardEntirely() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(listOf(item())))
        val repo = GetLineSessionRepository(api, seededStore())

        val subscription = repo.loadPreferredSubscription(provisionTrialIfEmpty = true)

        assertEquals("9", subscription.id)
        assertEquals(1, api.subscriptionCalls)
        assertEquals(0, api.dashboardCalls)
    }

    @Test
    fun emptyListProvisionsTrialThenRereads() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList(), listOf(item())),
            autoActivated = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())
        var announced = false

        val subscription = repo.loadPreferredSubscription(
            provisionTrialIfEmpty = true,
            onProvisioningTrial = { announced = true },
        )

        assertEquals("9", subscription.id)
        assertEquals(2, api.subscriptionCalls)
        assertEquals(1, api.dashboardCalls)
        assertTrue(announced)
    }

    @Test
    fun loadPreferredSubscriptionWithList_usesSecondResponseAfterTrial() = runBlocking {
        // First read has no importable preferred (no links) so trial runs; second
        // response is the one kept in PreferredSubscriptionLoad.all.
        val leaky = item().copy(
            id = "must-not-leak",
            isPrimary = false,
            subscriptionLink = null,
        )
        val afterTrial = item()
        val other = item().copy(
            id = "other",
            isPrimary = false,
            subscriptionLink = environmentLink() + "/other",
        )
        val api = FakeApi(
            itemsPerCall = listOf(
                listOf(leaky),
                listOf(other, afterTrial),
            ),
            autoActivated = true,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        val load = repo.loadPreferredSubscriptionWithList(provisionTrialIfEmpty = true)

        assertEquals("9", load.preferred.id)
        assertEquals(2, load.all.size)
        assertEquals(listOf("other", "9"), load.all.map { it.id })
        assertEquals(2, api.subscriptionCalls)
        assertFalse(load.all.any { it.id == "must-not-leak" })
    }

    @Test
    fun loadPreferredSubscriptionWithList_returnsFullListWithoutTrial() = runBlocking {
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
    }

    /** Trial already spent: nothing was created, so re-reading the list is waste. */
    @Test
    fun noActivationSkipsTheSecondRead() = runBlocking {
        val api = FakeApi(
            itemsPerCall = listOf(emptyList(), listOf(item())),
            autoActivated = false,
        )
        val repo = GetLineSessionRepository(api, seededStore())

        try {
            repo.loadPreferredSubscription(provisionTrialIfEmpty = true)
            fail("expected no importable subscription")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
        assertEquals(1, api.subscriptionCalls)
        assertEquals(1, api.dashboardCalls)
    }

    /** Best-effort: a broken dashboard degrades to the pre-existing error path. */
    @Test
    fun dashboardFailureDoesNotEscape() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(emptyList()), failDashboard = true)
        val repo = GetLineSessionRepository(api, seededStore())

        try {
            repo.loadPreferredSubscription(provisionTrialIfEmpty = true)
            fail("expected no importable subscription")
        } catch (_: GetLineAuthException.Protocol) {
            // expected — not the HttpFailure the dashboard call threw
        }
        assertEquals(1, api.dashboardCalls)
    }

    /** Repair paths run for users who had a subscription; they must not activate. */
    @Test
    fun defaultDoesNotProvision() = runBlocking {
        val api = FakeApi(itemsPerCall = listOf(emptyList()))
        val repo = GetLineSessionRepository(api, seededStore())
        var announced = false

        try {
            repo.loadPreferredSubscription(onProvisioningTrial = { announced = true })
            fail("expected no importable subscription")
        } catch (_: GetLineAuthException.Protocol) {
            // expected
        }
        assertEquals(0, api.dashboardCalls)
        assertFalse(announced)
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

    /** [itemsPerCall] supplies one subscriptions payload per call, last one repeats. */
    private class FakeApi(
        private val itemsPerCall: List<List<SubscriptionItem>>,
        private val autoActivated: Boolean = false,
        private val failDashboard: Boolean = false,
    ) : GetLineAuthApi {
        var subscriptionCalls = 0
        var dashboardCalls = 0

        override suspend fun startBrowserAuth(method: AuthMethod) = error("not used")
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
                trialEnabled = true,
                trialAvailable = false,
                trialAutoActivated = autoActivated,
                trialDays = 3,
            )
        }
    }
}
