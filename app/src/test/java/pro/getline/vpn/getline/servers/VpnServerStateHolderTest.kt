package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServerStateHolderTest {
    @Test
    fun needsInitialLoad_trueForLoadingAndVpnStopped() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.needsInitialLoad())

        holder.applyVpnStopped()
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
    fun applySelected_updatesReadyOnly() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selected = "A"))
        assertTrue(holder.applySelected("B"))
        val ready = holder.state as VpnServerUiState.Ready
        assertEquals("B", ready.selectedName)

        assertFalse(holder.applySelected("missing"))
        holder.applyVpnStopped()
        assertFalse(holder.applySelected("A"))
    }

    @Test
    fun applySelected_rejectsWhenNotSelectable() {
        val holder = VpnServerStateHolder()
        assertTrue(holder.beginInitialLoad())
        holder.applyLoadResult(sampleSuccess(selectable = false))
        assertFalse(holder.applySelected("B"))
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

        assertTrue(holder.shouldHealthCheck(nowMs = 1_000L))
    }

    @Test
    fun healthCheck_skippedWithinInterval() {
        val holder = VpnServerStateHolder()
        holder.onHealthCheckStarted(nowMs = 1_000L)

        // Repeated resumes/rebinds must not re-probe every node.
        assertFalse(holder.shouldHealthCheck(nowMs = 1_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 20_000L))
        assertFalse(holder.shouldHealthCheck(nowMs = 30_999L))
    }

    @Test
    fun healthCheck_runsAgainAfterInterval() {
        val holder = VpnServerStateHolder()
        holder.onHealthCheckStarted(nowMs = 1_000L)

        assertTrue(holder.shouldHealthCheck(nowMs = 31_000L))
    }

    @Test
    fun healthCheck_forcedAfterVpnStopped() {
        val holder = VpnServerStateHolder()
        holder.onHealthCheckStarted(nowMs = 1_000L)
        assertFalse(holder.shouldHealthCheck(nowMs = 2_000L))

        // Delays measured through a dead tunnel must not be reused.
        holder.applyVpnStopped()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun healthCheck_forcedAfterInvalidate() {
        val holder = VpnServerStateHolder()
        holder.onHealthCheckStarted(nowMs = 1_000L)

        holder.invalidate()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
    }

    @Test
    fun healthCheck_explicitInvalidateOnly() {
        val holder = VpnServerStateHolder()
        holder.onHealthCheckStarted(nowMs = 1_000L)

        holder.invalidateHealthCheck()

        assertTrue(holder.shouldHealthCheck(nowMs = 2_000L))
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
            ),
            selectedName = selected,
            selectable = selectable,
        )
    }
}
