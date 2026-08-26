package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess())
        assertTrue(holder.state is VpnServerUiState.Ready)
        assertFalse(holder.needsInitialLoad())
        assertNull(holder.beginInitialLoad())
    }

    @Test
    fun parallelLoad_secondPressRejected() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        assertNull(holder.beginInitialLoad())
        assertNull(holder.beginRefresh())
    }

    @Test
    fun emptyList_showsEmpty() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Empty)
        assertTrue(holder.state is VpnServerUiState.Empty)
        assertFalse(holder.needsInitialLoad())
    }

    @Test
    fun failure_showsFailed() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Failed)
        assertTrue(holder.state is VpnServerUiState.Failed)
    }

    @Test
    fun beginSelection_updatesReadyAndTracksPending() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        val selection = holder.beginSelection("B")!!
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("B", ready.selectedName)
        assertEquals(selection, holder.pendingSelection)
        assertFalse(holder.isSelectionConfirmed("B"))

        assertNull(holder.beginSelection("missing"))
        holder.invalidateInventory()
        assertNull(holder.beginSelection("A"))
    }

    @Test
    fun beginSelection_rejectsWhenNotSelectable() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
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
        assertNotNull(holder.beginReconcile())

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
        holder.invalidateInventory()
        assertEquals(
            VpnServerStateHolder.SelectionCompletion.Stale,
            holder.completeSelection(pending, success = true),
        )
    }

    @Test
    fun invalidate_allowsReload() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess())
        holder.invalidateInventory()
        assertTrue(holder.state is VpnServerUiState.Loading)
        assertTrue(holder.needsInitialLoad())
    }

    @Test
    fun beginReconcile_keepsReadyVisible() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        assertNotNull(holder.beginReconcile())
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("A", ready.selectedName)
        assertTrue(holder.requestInFlight)
        assertNull(holder.beginReconcile())
        assertNull(holder.beginRefresh())
    }

    @Test
    fun beginReconcile_fromEmpty_goesLoading() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(VpnServerLoadResult.Empty)
        assertNotNull(holder.beginReconcile())
        assertTrue(holder.state is VpnServerUiState.Loading)
        assertTrue(holder.requestInFlight)
    }

    @Test
    fun reconcileResult_updatesSelectedName() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        assertNotNull(holder.beginReconcile())
        holder.applyLoadResult(sampleSuccess(selected = "B"))
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("B", ready.selectedName)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun healthCheck_runsOnFirstLoad() {
        val holder = VpnServerStateHolder()

        assertTrue(holder.shouldHealthCheck(nowMs = 1_000L))
    }

    @Test
    fun healthCheck_skippedWithinInterval() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)

        // Repeated resumes/rebinds must not re-probe every node.
        assertFalse(holder.shouldHealthCheck(nowMs = 1_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 20_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 30_999L))
    }

    @Test
    fun healthCheck_runsAgainAfterInterval() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)

        assertTrue(holder.shouldHealthCheck(nowMs = 31_000L))
    }

    @Test
    fun healthCheck_forcedAfterClearLiveDelays() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)
        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))

        holder.clearLiveDelays()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun clearLiveDelays_stripsReadyMeasurements() {
        val holder = VpnServerStateHolder()
        assertNotNull(holder.beginInitialLoad())
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
    fun healthCheck_explicitInvalidateOnly() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)

        holder.invalidateHealthCheck()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun healthCheck_isProbingWhileInFlight() {
        val holder = VpnServerStateHolder()
        assertFalse(holder.isProbing)

        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        assertTrue(holder.isProbing)
        assertFalse(holder.shouldHealthCheck(nowMs = 1_000L))

        assertTrue(holder.finishHealthCheck(token))
        assertFalse(holder.isProbing)
    }

    @Test
    fun inventoryInvalidation_preservesHealthCooldown() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)

        holder.invalidateInventory()

        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun requestCancelled_doesNotTouchProbeState() {
        val holder = VpnServerStateHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!

        holder.onRequestCancelled()

        // A cancelled load job's cleanup is not the probe's owner: the probe
        // it started (or one it never started) must survive untouched.
        assertTrue(holder.isProbing)
        assertTrue(holder.finishHealthCheck(token))
        assertFalse(holder.isProbing)
    }

    @Test
    fun requestCancelled_fromStaleJobCannotFinishNewerJobsProbe() {
        // Regression for the cross-job race: an old load job's cleanup used to
        // call a holder-wide "finish whatever probe is active" instead of
        // finishing its own token. Sequence that used to break: job A's probe
        // finishes on time, job B starts its own probe, and only *then* does
        // A's outer coroutine (cancelled earlier, for an unrelated reason)
        // unwind and run its cleanup.
        val holder = VpnServerStateHolder()
        val jobAToken = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(jobAToken)

        val jobBToken = holder.beginHealthCheck(nowMs = 40_000L)!!
        assertTrue(holder.isProbing)

        // Job A's late cleanup must not know or care about job B's probe.
        holder.onRequestCancelled()

        assertTrue(holder.isProbing)
        assertTrue(holder.finishHealthCheck(jobBToken))
        assertFalse(holder.isProbing)
    }

    @Test
    fun finishHealthCheck_ignoresStaleToken() {
        val holder = VpnServerStateHolder()
        val first = holder.beginHealthCheck(nowMs = 1_000L)!!
        assertTrue(holder.finishHealthCheck(first))

        val second = holder.beginHealthCheck(nowMs = 100_000L)!!
        assertFalse(holder.finishHealthCheck(first))
        assertTrue(holder.isProbing)

        assertTrue(holder.finishHealthCheck(second))
        assertFalse(holder.isProbing)
    }

    @Test
    fun reconcileWhileProbing_isRejectedSoTheProbeSurvives() {
        // Regression: requestInFlight alone used to gate reconcile/refresh, but
        // applyLoadResult clears it before the trailing probe starts. A reconcile
        // arriving mid-probe would pass that stale guard, and the Activity would
        // cancel the job that owns the probe to start a new one — so a probe
        // could never survive long enough to actually finish (looked like an
        // endlessly spinning progress indicator, with delays never populating).
        val holder = readyHolder()
        holder.beginHealthCheck(nowMs = 1_000L)!!

        assertNull(holder.beginReconcile())
        assertNull(holder.beginRefresh())
        assertNull(holder.beginInitialLoad())
        assertTrue(holder.isProbing)
    }

    @Test
    fun reconcileAllowedAgainOnceProbeFinishes() {
        val holder = readyHolder()
        val token = holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.finishHealthCheck(token)

        assertNotNull(holder.beginReconcile())
    }

    @Test
    fun reloadRejectedWhileProbing_isQueuedNotDropped() {
        // Regression: a rejected reconcile/refresh must not silently vanish — a
        // profile change or VPN restart arriving mid-probe is deferred to run
        // once the probe finishes, not lost until some unrelated later event.
        val holder = readyHolder()
        holder.beginHealthCheck(nowMs = 1_000L)!!

        assertNull(holder.beginReconcile())
        assertTrue(holder.consumePendingReload())
    }

    @Test
    fun refreshRejectedWhileProbing_alsoQueuesReload() {
        val holder = readyHolder()
        holder.beginHealthCheck(nowMs = 1_000L)!!

        assertNull(holder.beginRefresh())
        assertTrue(holder.consumePendingReload())
    }

    @Test
    fun consumePendingReload_isFalseByDefaultAndOneShot() {
        val holder = readyHolder()
        assertFalse(holder.consumePendingReload())

        holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.beginReconcile()
        assertTrue(holder.consumePendingReload())
        assertFalse(holder.consumePendingReload())
    }

    @Test
    fun requestCancelled_withToken_ignoresStaleToken() {
        val holder = VpnServerStateHolder()
        val first = holder.beginInitialLoad()!!
        holder.applyLoadResult(sampleSuccess())

        val second = holder.beginReconcile()!!
        assertTrue(holder.requestInFlight)

        // The first request's job finishing its own (late) cleanup must not
        // clear a guard the second request now owns.
        holder.onRequestCancelled(first)

        assertTrue(holder.requestInFlight)
        holder.onRequestCancelled(second)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun deferredReload_selfRelaunchDoesNotLoseTheNewRequestsGuard() {
        // Regression for the exact race this token exists to prevent: job A
        // finishes its own load+probe, finds a reload was queued during the
        // probe, and starts job B *from inside its own cleanup* — the same
        // shape as startServerLoadJob cancelling `serverLoadJob` (itself) to
        // launch the replacement. Job A's own outer job-cancellation cleanup
        // then runs (it did cancel itself) and must not clear job B's guard,
        // or a third, unrelated event could cancel job B before it finishes.
        val holder = readyHolder()
        val jobAToken = holder.beginReconcile()!!
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        val probe = holder.beginHealthCheck(nowMs = 1_000L)!!

        // A reload arrives mid-probe and is queued rather than dropped.
        assertNull(holder.beginReconcile())
        assertTrue(holder.consumePendingReload())

        // Probe finishes; job A reacts to the queued reload by starting job B.
        holder.finishHealthCheck(probe)
        val jobBToken = holder.beginReconcile()!!
        assertTrue(holder.requestInFlight)

        // Job A's own outer-job cleanup runs late, with job A's own token.
        holder.onRequestCancelled(jobAToken)

        assertTrue(holder.requestInFlight)
        holder.onRequestCancelled(jobBToken)
        assertFalse(holder.requestInFlight)
    }

    @Test
    fun clearLiveDelays_dropsAPendingReload() {
        // A VPN stop supersedes any reload queued for the probe it abandons —
        // the ClashStop/ServiceRecreated handler decides the reload from scratch.
        val holder = readyHolder()
        holder.beginHealthCheck(nowMs = 1_000L)!!
        holder.beginReconcile()
        assertTrue(holder.isProbing)

        holder.clearLiveDelays()

        assertFalse(holder.consumePendingReload())
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
