package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionStateHolderTest {
    @Test
    fun needsInitialLoad_trueOnlyForLoadingWithoutInFlight() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.needsInitialLoad())

        assertTrue(holder.beginInitialLoad())
        assertFalse(holder.needsInitialLoad())
    }

    @Test
    fun localCard_isReadyIndependentOfAccountSignal() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())

        holder.applyLocalResult(
            presentation = samplePresentation(title = "Standard"),
            failed = false,
        )

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Standard", ready.subscription.title)
        assertFalse(holder.needsInitialLoad())
    }

    @Test
    fun noManagedProfile_isEmpty() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())

        holder.applyLocalResult(presentation = null, failed = false)

        assertTrue(holder.state is SubscriptionUiState.Empty)
    }

    @Test
    fun unavailableAccountSignal_doesNotStartManagedCardRefresh() {
        val holder = readyHolder("Local")
        val signal = SubscriptionAccountSignal.Unavailable

        if (signal.shouldRefreshManagedProfile(hasManagedBinding = true)) {
            holder.beginRefresh()
        }

        assertFalse(holder.requestInFlight)
        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Local", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertFalse(ready.transientError)
    }

    @Test
    fun activeAccountSignal_startsManagedCardRefresh() {
        val holder = readyHolder("Local")
        val signal = SubscriptionAccountSignal.RefreshManagedProfile

        if (signal.shouldRefreshManagedProfile(hasManagedBinding = true)) {
            holder.beginRefresh()
        }

        assertTrue(holder.requestInFlight)
        assertTrue((holder.state as SubscriptionUiState.Ready).isRefreshing)
    }

    @Test
    fun parallelRefresh_secondPressRejected_andKeepsCardVisible() {
        val holder = readyHolder("Local")

        assertTrue(holder.beginRefresh())
        assertFalse(holder.beginRefresh())

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Local", ready.subscription.title)
        assertTrue(ready.isRefreshing)
    }

    @Test
    fun localRefreshFailure_keepsReadyCard() {
        val holder = readyHolder("Local")
        assertTrue(holder.beginRefresh())

        holder.applyLocalResult(presentation = null, failed = true)

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Local", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertTrue(ready.transientError)
    }

    @Test
    fun configRefreshFailure_withReadableSnapshot_keepsLocalCardReady() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())

        holder.applyLocalResult(
            presentation = samplePresentation("Saved"),
            failed = true,
        )

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Saved", ready.subscription.title)
        assertTrue(ready.transientError)
    }

    @Test
    fun firstLocalReadFailure_showsFailed() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())

        holder.applyLocalResult(presentation = null, failed = true)

        assertTrue(holder.state is SubscriptionUiState.Failed)
    }

    @Test
    fun successfulRefresh_replacesCardAtomically() {
        val holder = readyHolder("Old")
        assertTrue(holder.beginRefresh())

        holder.applyLocalResult(
            presentation = samplePresentation(title = "New"),
            failed = false,
        )

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("New", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertFalse(ready.transientError)
    }

    @Test
    fun confirmedMissingProfile_afterRefresh_becomesEmpty() {
        val holder = readyHolder("Old")
        assertTrue(holder.beginRefresh())

        holder.applyLocalResult(presentation = null, failed = false)

        assertTrue(holder.state is SubscriptionUiState.Empty)
    }

    @Test
    fun staleCompletion_isIgnored() {
        val holder = readyHolder("Old")
        assertTrue(holder.beginRefresh())
        val first = holder.flightGeneration
        assertTrue(holder.beginRefresh(supersede = true))
        val second = holder.flightGeneration

        holder.applyLocalResult(
            presentation = samplePresentation("Stale"),
            failed = false,
            generation = first,
        )
        assertTrue(holder.requestInFlight)
        assertEquals("Old", (holder.state as SubscriptionUiState.Ready).subscription.title)

        holder.applyLocalResult(
            presentation = samplePresentation("Current"),
            failed = false,
            generation = second,
        )
        assertEquals("Current", (holder.state as SubscriptionUiState.Ready).subscription.title)
    }

    @Test
    fun cancellation_clearsRefreshing_withoutChangingCard() {
        val holder = readyHolder("Local")
        assertTrue(holder.beginRefresh())
        val generation = holder.flightGeneration

        holder.onRequestCancelled(generation)

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Local", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertFalse(holder.requestInFlight)
    }

    private fun readyHolder(title: String): SubscriptionStateHolder {
        return SubscriptionStateHolder().also { holder ->
            assertTrue(holder.beginInitialLoad())
            holder.applyLocalResult(
                presentation = samplePresentation(title),
                failed = false,
            )
        }
    }

    private fun samplePresentation(title: String = "Trial"): SubscriptionPresentation {
        return SubscriptionPresentation(
            id = "1",
            title = title,
            isActive = true,
            expireAtEpochMillis = 1_700_000_000_000L,
            daysLeft = 2,
            deviceLimit = 3,
            trafficUsedBytes = 100L,
            trafficLimitBytes = 1000L,
            trafficUnlimited = false,
        )
    }
}
