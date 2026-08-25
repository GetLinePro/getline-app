package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServerStateHolderTest {
    @Test
    fun needsInitialLoad_trueForLoading() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.needsInitialLoad())
    }

    @Test
    fun tabSwitch_doesNotNeedSecondFetch_whenReady() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess())
        assertTrue(holder.state is VpnServerUiState.Ready)
        assertFalse(holder.needsInitialLoad())
        assertFalse(holder.beginInitialLoad())
    }

    @Test
    fun parallelLoad_secondPressRejected() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        assertFalse(holder.beginInitialLoad())
        assertFalse(holder.beginRefresh())
    }

    @Test
    fun emptyList_showsEmpty() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Empty)
        assertTrue(holder.state is VpnServerUiState.Empty)
        assertFalse(holder.needsInitialLoad())
    }

    @Test
    fun failure_showsFailed() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Failed)
        assertTrue(holder.state is VpnServerUiState.Failed)
    }

    @Test
    fun beginSelection_updatesReadyAndTracksPending() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        val selection = holder.beginSelection("B")!!
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("B", ready.selectedName)
        assertEquals(selection, holder.pendingSelection)
        assertFalse(holder.isSelectionConfirmed("B"))

        assertNull(holder.beginSelection("missing"))
        holder.invalidate()
        assertNull(holder.beginSelection("A"))
    }

    @Test
    fun beginSelection_rejectsWhenNotSelectable() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selectable = false))
        assertNull(holder.beginSelection("B"))
    }

    @Test
    fun staleFailure_doesNotClearNewerSelection() {
        val holder = readyHolder()
        val first = holder.beginSelection("B")!!
        val second = holder.beginSelection("A")!!

        assertEquals(
            VpnServerStateHolder.SelectionCompletion.Stale,
            holder.completeSelection(first, success = false),
        )
        assertEquals(second, holder.pendingSelection)
        assertEquals("A", (holder.state as VpnServerUiState.Ready).selectedName)
    }

    @Test
    fun staleSuccess_doesNotSettleNewerSelection() {
        val holder = readyHolder()
        val first = holder.beginSelection("B")!!
        val second = holder.beginSelection("A")!!

        assertEquals(
            VpnServerStateHolder.SelectionCompletion.Stale,
            holder.completeSelection(first, success = true),
        )
        assertEquals(second, holder.pendingSelection)
        assertFalse(holder.isSelectionConfirmed("A"))
    }

    @Test
    fun latestSuccess_confirmsSelection() {
        val holder = readyHolder()
        val selection = holder.beginSelection("B")!!

        assertEquals(
            VpnServerStateHolder.SelectionCompletion.LatestSuccess,
            holder.completeSelection(selection, success = true),
        )
        assertNull(holder.pendingSelection)
        assertTrue(holder.isSelectionConfirmed("B"))
    }

    @Test
    fun latestFailure_clearsOptimisticSelectionForReload() {
        val holder = readyHolder()
        val selection = holder.beginSelection("B")!!

        assertEquals(
            VpnServerStateHolder.SelectionCompletion.LatestFailure,
            holder.completeSelection(selection, success = false),
        )
        assertNull(holder.pendingSelection)
        assertTrue(holder.state is VpnServerUiState.Loading)
        assertFalse(holder.isSelectionConfirmed("B"))
    }

    @Test
    fun loadResult_reappliesPendingSelection() {
        val holder = readyHolder()
        val pending = holder.beginSelection("B")!!
        assertTrue(holder.beginReconcile())

        holder.applyLoadResult(sampleSuccess(selected = "A"))

        assertEquals(pending, holder.pendingSelection)
        assertEquals("B", (holder.state as VpnServerUiState.Ready).selectedName)
    }

    @Test
    fun generation_distinguishesAtoBtoA() {
        val holder = readyHolder()
        val firstA = holder.beginSelection("A")!!
        holder.beginSelection("B")!!
        val secondA = holder.beginSelection("A")!!

        assertTrue(firstA.generation != secondA.generation)
        assertEquals(
            VpnServerStateHolder.SelectionCompletion.Stale,
            holder.completeSelection(firstA, success = true),
        )
        assertEquals(secondA, holder.pendingSelection)
    }

    @Test
    fun invalidate_makesPendingCompletionStale() {
        val holder = readyHolder()
        val pending = holder.beginSelection("B")!!
        holder.invalidate()
        assertEquals(
            VpnServerStateHolder.SelectionCompletion.Stale,
            holder.completeSelection(pending, success = true),
        )
    }

    @Test
    fun invalidate_allowsReload() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess())
        holder.invalidate()
        assertTrue(holder.state is VpnServerUiState.Loading)
        assertTrue(holder.needsInitialLoad())
    }

    @Test
    fun beginReconcile_keepsReadyVisible() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        assertTrue(holder.beginReconcile())
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("A", ready.selectedName)
        assertTrue(holder.requestInFlight)
        assertFalse(holder.beginReconcile())
        assertFalse(holder.beginRefresh())
    }

    @Test
    fun beginReconcile_fromEmpty_goesLoading() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Empty)
        assertTrue(holder.beginReconcile())
        assertTrue(holder.state is VpnServerUiState.Loading)
        assertTrue(holder.requestInFlight)
    }

    @Test
    fun reconcileResult_updatesSelectedName() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        assertTrue(holder.beginReconcile())
        holder.applyLoadResult(sampleSuccess(selected = "B"))
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("B", ready.selectedName)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun healthCheck_runsOnFirstLoad() {
        val holder = VpnServerStateHolder()

        holder.onVpnStarted()
        assertTrue(holder.shouldHealthCheck(nowMs = 1_000L))
    }

    @Test
    fun healthCheck_skippedWithinInterval() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)

        // Repeated resumes/rebinds must not re-probe every node.
        assertFalse(holder.shouldHealthCheck(nowMs = 1_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 20_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 30_999L))
    }

    @Test
    fun healthCheck_runsAgainAfterInterval() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)

        assertTrue(holder.shouldHealthCheck(nowMs = 31_000L))
        assertEquals(VpnServerStateHolder.LatencyProbeState.Idle, holder.latencyProbeState)
    }

    @Test
    fun healthCheck_forcedAfterClearLiveDelays() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)
        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))

        holder.clearLiveDelays()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun clearLiveDelays_stripsReadyMeasurements() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(
            VpnServerLoadResult.Success(
                groupName = "VPN",
                servers = listOf(VpnServerItem("A", "A", delayMs = 42)),
                selectedName = "A",
                selectable = true,
            ),
        )

        holder.clearLiveDelays()

        val ready = holder.state as VpnServerUiState.Ready
        assertNull(ready.servers.single().delayMs)
        assertTrue(holder.shouldHealthCheck(nowMs = 1L))
    }

    @Test
    fun healthCheck_forcedAfterInvalidate() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)

        holder.invalidateForVpnRestart()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun healthCheck_explicitInvalidateOnly() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)

        holder.invalidateHealthCheck()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun healthCheck_transitionsThroughProbeAndCooldown() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        assertEquals(VpnServerStateHolder.LatencyProbeState.Idle, holder.latencyProbeState)

        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        assertEquals(VpnServerStateHolder.LatencyProbeState.Probing, holder.latencyProbeState)

        assertTrue(holder.finishHealthCheck(probe))
        assertEquals(
            VpnServerStateHolder.LatencyProbeState.Cooldown(startedAtMs = 1_000L),
            holder.latencyProbeState,
        )
    }

    @Test
    fun invalidateHealthCheck_doesNotBreakActiveProbe() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!

        holder.invalidateHealthCheck()

        assertEquals(VpnServerStateHolder.LatencyProbeState.Probing, holder.latencyProbeState)
        assertTrue(holder.finishHealthCheck(probe))
        assertEquals(
            VpnServerStateHolder.LatencyProbeState.Cooldown(startedAtMs = 1_000L),
            holder.latencyProbeState,
        )
    }

    @Test
    fun inventoryInvalidation_preservesHealthCooldown() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(probe)

        holder.invalidateInventory()

        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun cancelledProbe_stopsProgressAndKeepsCooldown() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        holder.beginHealthCheck(nowMs = 1_000L)

        holder.onRequestCancelled()

        assertEquals(
            VpnServerStateHolder.LatencyProbeState.Cooldown(startedAtMs = 1_000L),
            holder.latencyProbeState,
        )
        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun stoppedVpn_makesProbeUnavailableAndStaleFinishIsIgnored() {
        val holder = VpnServerStateHolder()
        holder.onVpnStarted()
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!

        holder.onVpnStopped()

        assertEquals(VpnServerStateHolder.LatencyProbeState.Unavailable, holder.latencyProbeState)
        assertFalse(holder.finishHealthCheck(probe))
    }

    private fun sampleSuccess(
        selected: String = "A",
        selectable: Boolean = true,
    ): VpnServerLoadResult.Success {
        return VpnServerLoadResult.Success(
            groupName = "VPN",
            servers = listOf(
                VpnServerItem("A", "A"),
                VpnServerItem("B", "B"),
                VpnServerItem("C", "C"),
            ),
            selectedName = selected,
            selectable = selectable,
        )
    }

    private fun readyHolder(): VpnServerStateHolder {
        return VpnServerStateHolder().apply {
            beginInitialLoad()
            applyLoadResult(sampleSuccess(selected = "C"))
        }
    }
}
