package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun tabSwitch_doesNotNeedSecondFetch_whenReady() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem()),
            presentation = samplePresentation(),
        )
        assertTrue(holder.state is SubscriptionUiState.Ready)
        assertFalse(holder.needsInitialLoad())
        assertFalse(holder.beginInitialLoad())
    }

    @Test
    fun parallelRefresh_secondPressRejected() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem()),
            presentation = samplePresentation(),
        )
        assertTrue(holder.beginRefresh())
        assertFalse(holder.beginRefresh())
        assertTrue((holder.state as SubscriptionUiState.Ready).isRefreshing)
    }

    @Test
    fun transientRefreshFailure_keepsReadyCard() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        val presentation = samplePresentation(title = "Trial")
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem()),
            presentation = presentation,
        )
        assertTrue(holder.beginRefresh())
        holder.applyLoadResult(SubscriptionLoadResult.TransientFailure, presentation = null)

        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("Trial", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertTrue(ready.transientError)
    }

    @Test
    fun firstRequestFailure_showsFailed() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(SubscriptionLoadResult.TransientFailure, presentation = null)
        assertTrue(holder.state is SubscriptionUiState.Failed)
    }

    @Test
    fun emptySubscriptions_showsEmpty() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = null),
            presentation = null,
        )
        assertTrue(holder.state is SubscriptionUiState.Empty)
    }

    @Test
    fun signedOut_withAndWithoutProfile() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(hasImportedProfile = true)
        assertEquals(
            SubscriptionUiState.SignedOut(hasImportedProfile = true),
            holder.state,
        )
        holder.applySignedOut(hasImportedProfile = false)
        assertEquals(
            SubscriptionUiState.SignedOut(hasImportedProfile = false),
            holder.state,
        )
    }

    @Test
    fun invalidateSessionState_fromSignedOut_allowsForcedReload() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(hasImportedProfile = true)
        assertFalse(holder.needsInitialLoad())

        holder.invalidateSessionState()
        assertTrue(holder.state is SubscriptionUiState.Loading)
        assertTrue(holder.needsInitialLoad())
        assertTrue(holder.beginRefresh())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem()),
            presentation = samplePresentation(),
        )
        assertTrue(holder.state is SubscriptionUiState.Ready)
    }

    @Test
    fun successReplacesPresentationAtomically() {
        val holder = SubscriptionStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem(id = "1")),
            presentation = samplePresentation(title = "Old"),
        )
        assertTrue(holder.beginRefresh())
        holder.applyLoadResult(
            SubscriptionLoadResult.Success(preferred = sampleItem(id = "2")),
            presentation = samplePresentation(title = "New"),
        )
        val ready = holder.state as SubscriptionUiState.Ready
        assertEquals("New", ready.subscription.title)
        assertFalse(ready.isRefreshing)
        assertFalse(ready.transientError)
    }

    @Test
    fun beginLinkOnlyRefresh_keepsCardAndSetsRefreshing() {
        val holder = SubscriptionStateHolder()
        val linkOnly = LinkOnlyPresentation(
            expireAtEpochMillis = 1_700_000_000_000L,
            trafficUsedBytes = 10L,
            trafficLimitBytes = 100L,
        )
        holder.applySignedOut(hasImportedProfile = true, linkOnly = linkOnly)
        val gen = holder.beginLinkOnlyRefresh()
        assertTrue(gen != null)
        val signedOut = holder.state as SubscriptionUiState.SignedOut
        assertEquals(linkOnly, signedOut.linkOnly)
        assertTrue(signedOut.isRefreshing)
        assertFalse(signedOut.refreshFailed)
        assertTrue(holder.requestInFlight)
        assertEquals(gen, holder.flightGeneration)
    }

    @Test
    fun beginLinkOnlyRefresh_secondCallRejected() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(
            hasImportedProfile = true,
            linkOnly = LinkOnlyPresentation(null, 0L, 100L),
        )
        assertTrue(holder.beginLinkOnlyRefresh() != null)
        assertNull(holder.beginLinkOnlyRefresh())
    }

    @Test
    fun onRequestCancelled_clearsLinkOnlyRefreshing() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(
            hasImportedProfile = true,
            linkOnly = LinkOnlyPresentation(null, null, null),
        )
        val gen = holder.beginLinkOnlyRefresh()!!
        holder.onRequestCancelled(gen)
        val signedOut = holder.state as SubscriptionUiState.SignedOut
        assertFalse(signedOut.isRefreshing)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun onRequestCancelled_staleGenerationIgnored() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(
            hasImportedProfile = true,
            linkOnly = LinkOnlyPresentation(null, null, null),
        )
        val first = holder.beginLinkOnlyRefresh()!!
        // Supersede as host would after cancel + new begin.
        val second = holder.beginLinkOnlyRefresh(supersede = true)!!
        assertTrue(second > first)
        holder.onRequestCancelled(first)
        assertTrue(holder.requestInFlight)
        assertTrue((holder.state as SubscriptionUiState.SignedOut).isRefreshing)
        holder.onRequestCancelled(second)
        assertFalse(holder.requestInFlight)
        assertFalse((holder.state as SubscriptionUiState.SignedOut).isRefreshing)
    }

    @Test
    fun applyLinkOnlyRefreshResult_nullRemovesCard() {
        val holder = SubscriptionStateHolder()
        holder.applySignedOut(
            hasImportedProfile = true,
            linkOnly = LinkOnlyPresentation(1L, 2L, 3L),
        )
        val gen = holder.beginLinkOnlyRefresh()!!
        // Confirmed absence after successful inventory — not a transient IPC failure.
        holder.applyLinkOnlyRefreshResult(linkOnly = null, failed = true, generation = gen)
        val signedOut = holder.state as SubscriptionUiState.SignedOut
        assertNull(signedOut.linkOnly)
        assertFalse(signedOut.isRefreshing)
        assertTrue(signedOut.refreshFailed)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun applyLinkOnlyRefreshResult_failedKeepsLastKnownCard() {
        val holder = SubscriptionStateHolder()
        val card = LinkOnlyPresentation(1L, 2L, 3L)
        holder.applySignedOut(hasImportedProfile = true, linkOnly = card)
        val gen = holder.beginLinkOnlyRefresh()!!
        // Transient snapshot/update failure: host re-passes previous presentation.
        holder.applyLinkOnlyRefreshResult(linkOnly = card, failed = true, generation = gen)
        val signedOut = holder.state as SubscriptionUiState.SignedOut
        assertEquals(card, signedOut.linkOnly)
        assertFalse(signedOut.isRefreshing)
        assertTrue(signedOut.refreshFailed)
    }

    @Test
    fun applyLinkOnlyRefreshResult_staleGenerationIgnored() {
        val holder = SubscriptionStateHolder()
        val card = LinkOnlyPresentation(1L, 2L, 3L)
        holder.applySignedOut(hasImportedProfile = true, linkOnly = card)
        val first = holder.beginLinkOnlyRefresh()!!
        val second = holder.beginLinkOnlyRefresh(supersede = true)!!
        holder.applyLinkOnlyRefreshResult(linkOnly = null, failed = false, generation = first)
        // Stale completion must not wipe the superseding refresh.
        assertTrue(holder.requestInFlight)
        assertEquals(card, (holder.state as SubscriptionUiState.SignedOut).linkOnly)
        holder.applyLinkOnlyRefreshResult(linkOnly = card, failed = true, generation = second)
        assertFalse(holder.requestInFlight)
        assertTrue((holder.state as SubscriptionUiState.SignedOut).refreshFailed)
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

    private fun sampleItem(id: String = "1"): SubscriptionItem {
        return SubscriptionItem(
            id = id,
            name = "n",
            planName = "Trial",
            planType = "trial",
            kind = "trial",
            isPrimary = true,
            isActive = true,
            expireAtEpochMillis = 1_700_000_000_000L,
            daysLeft = 2,
            deviceLimit = 3,
            totalDeviceLimit = 3,
            devicesCount = -1,
            traffic = SubscriptionTraffic(100L, 1000L, 10.0, false),
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = "https://example.test/sub",
        )
    }
}
