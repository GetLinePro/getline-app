package pro.getline.vpn.getline.servers

/**
 * Activity-scoped owner for Servers destination state.
 *
 * Survives tab switches within the same Activity. Does not call Clash or touch VPN.
 */
class VpnServerStateHolder {
    sealed interface LatencyProbeState {
        data object Unavailable : LatencyProbeState
        data object Idle : LatencyProbeState
        data object Probing : LatencyProbeState
        data class Cooldown(val startedAtMs: Long) : LatencyProbeState
    }

    data class LatencyProbe(
        internal val generation: Long,
    )

    data class Selection(
        val name: String,
        val generation: Long,
    )

    enum class SelectionCompletion {
        LatestSuccess,
        LatestFailure,
        Stale,
    }

    var state: VpnServerUiState = VpnServerUiState.Loading
        private set

    var requestInFlight: Boolean = false
        private set

    var latencyProbeState: LatencyProbeState = LatencyProbeState.Unavailable
        private set

    private var lastHealthCheckAt: Long? = null
    private var nextLatencyProbeGeneration = 0L
    private var activeLatencyProbe: LatencyProbe? = null

    private var nextSelectionGeneration = 0L

    var pendingSelection: Selection? = null
        private set

    private val preferredByGroup = mutableMapOf<String, String>()

    /**
     * Last variant the user picked, per group key.
     *
     * A country row derives its primary from this before falling back to
     * delay ranking: without it, picking a variant and then switching to
     * another country visibly reset the first one to the fastest node.
     *
     * A preference is a hint only — the core's reported selection always wins.
     */
    val preferences: Map<String, String>
        get() = preferredByGroup

    /**
     * Whether a latency measurement should run for this load.
     *
     * Health checks probe every node in the group, so they must not fire on each
     * resume or rebind. One run per [HEALTH_CHECK_INTERVAL_MS] is enough to keep
     * the fastest-variant ranking meaningful.
     *
     * @param nowMs monotonic clock (SystemClock.elapsedRealtime), not wall time.
     */
    fun shouldHealthCheck(nowMs: Long): Boolean {
        if (activeLatencyProbe != null) return false
        val last = lastHealthCheckAt ?: return true
        val eligible = nowMs - last >= HEALTH_CHECK_INTERVAL_MS
        if (eligible && latencyProbeState is LatencyProbeState.Cooldown) {
            latencyProbeState = LatencyProbeState.Idle
        }
        return eligible
    }

    /** Mark the VPN as a live source of latency measurements. */
    fun onVpnStarted() {
        if (activeLatencyProbe != null) return
        latencyProbeState = lastHealthCheckAt?.let { LatencyProbeState.Cooldown(it) }
            ?: LatencyProbeState.Idle
    }

    /** Drop live latency eligibility when the VPN stops. */
    fun onVpnStopped() {
        activeLatencyProbe = null
        lastHealthCheckAt = null
        latencyProbeState = LatencyProbeState.Unavailable
    }

    /**
     * Start one real health check, if it is eligible.
     *
     * The token prevents a cancelled/stale load job from finishing a newer probe.
     */
    fun beginHealthCheck(nowMs: Long): LatencyProbe? {
        if (!shouldHealthCheck(nowMs)) return null

        val probe = LatencyProbe(++nextLatencyProbeGeneration)
        lastHealthCheckAt = nowMs
        activeLatencyProbe = probe
        latencyProbeState = LatencyProbeState.Probing
        return probe
    }

    /** Finish only the still-current probe. */
    fun finishHealthCheck(probe: LatencyProbe): Boolean {
        if (activeLatencyProbe != probe) return false
        activeLatencyProbe = null
        latencyProbeState = lastHealthCheckAt?.let { LatencyProbeState.Cooldown(it) }
            ?: LatencyProbeState.Idle
        return true
    }

    /** Force the next eligible load to measure again. */
    fun invalidateHealthCheck() {
        if (activeLatencyProbe != null) return
        lastHealthCheckAt = null
        latencyProbeState = LatencyProbeState.Idle
    }

    /** Invalidate the server inventory without changing health-check eligibility. */
    fun invalidateInventory() {
        requestInFlight = false
        pendingSelection = null
        state = VpnServerUiState.Loading
    }

    /** Invalidate inventory and health-check eligibility for a VPN restart. */
    fun invalidateForVpnRestart() {
        invalidateInventory()
        onVpnStopped()
    }

    /**
     * Whether an automatic load should run when the Servers tab is shown.
     * Ready keeps last list; Empty/Failed wait for explicit retry;
     * Loading with no in-flight should fetch.
     */
    fun needsInitialLoad(): Boolean {
        if (requestInFlight) return false
        return when (state) {
            is VpnServerUiState.Loading -> true
            is VpnServerUiState.Ready,
            is VpnServerUiState.Empty,
            is VpnServerUiState.Failed -> false
        }
    }

    /**
     * Begin initial full-screen load.
     * Returns false if already in flight or a stable non-reload state already exists.
     */
    fun beginInitialLoad(): Boolean {
        if (requestInFlight) return false
        when (state) {
            is VpnServerUiState.Loading -> Unit
            else -> return false
        }
        requestInFlight = true
        state = VpnServerUiState.Loading
        return true
    }

    /**
     * Begin forced reload (retry, profile change, Clash start while on tab).
     * Returns false if a request is already in flight.
     */
    fun beginRefresh(): Boolean {
        if (requestInFlight) return false
        requestInFlight = true
        state = VpnServerUiState.Loading
        return true
    }

    /**
     * Soft re-query after Activity resume.
     *
     * Keeps a [VpnServerUiState.Ready] list visible (no full-screen loading flash)
     * while still re-reading Mihomo — external ProxyActivity selection emits no
     * profile/Clash lifecycle event.
     *
     * Non-Ready states go to Loading. Returns false if a request is already in flight.
     */
    fun beginReconcile(): Boolean {
        if (requestInFlight) return false
        requestInFlight = true
        when (state) {
            is VpnServerUiState.Ready -> Unit
            else -> state = VpnServerUiState.Loading
        }
        return true
    }

    /** Drop live latency after the tunnel stops; the inventory stays. */
    fun clearLiveDelays() {
        onVpnStopped()
        val ready = state as? VpnServerUiState.Ready ?: return
        if (ready.servers.none { it.delayMs != null }) return
        state = ready.copy(servers = ready.servers.map { it.copy(delayMs = null) })
    }

    fun applyLoadResult(result: VpnServerLoadResult) {
        requestInFlight = false
        state = when (result) {
            is VpnServerLoadResult.Success -> {
                if (result.servers.isEmpty()) {
                    VpnServerUiState.Empty
                } else {
                    // The core is the source of truth; adopt its selection as the
                    // preference so an external change is not fought by the UI.
                    result.selectedName.takeIf { it.isNotBlank() }?.let { rememberChoice(it) }
                    VpnServerUiState.Ready(
                        groupName = result.groupName,
                        servers = result.servers,
                        selectedName = result.selectedName,
                        selectable = result.selectable,
                    )
                }
            }
            VpnServerLoadResult.Empty -> VpnServerUiState.Empty
            VpnServerLoadResult.Failed -> VpnServerUiState.Failed
        }
        reapplyPendingSelection()
    }

    /**
     * Start a latest-wins optimistic selection. Generation distinguishes A → B → A,
     * where comparing names would let the first A settle the second A incorrectly.
     */
    fun beginSelection(name: String): Selection? {
        val ready = state as? VpnServerUiState.Ready ?: return null
        if (!ready.selectable) return null
        if (ready.servers.none { it.name == name }) return null

        val selection = Selection(
            name = name,
            generation = ++nextSelectionGeneration,
        )
        pendingSelection = selection
        state = ready.copy(selectedName = name)
        rememberChoice(name)
        return selection
    }

    fun isSelectionConfirmed(name: String): Boolean {
        val ready = state as? VpnServerUiState.Ready ?: return false
        return pendingSelection == null && ready.selectedName == name
    }

    /**
     * Complete only the generation that is still current. A stale failure must not
     * clear/reload over a newer tap; a stale success must not navigate away from it.
     */
    fun completeSelection(
        selection: Selection,
        success: Boolean,
    ): SelectionCompletion {
        if (pendingSelection?.generation != selection.generation) {
            return SelectionCompletion.Stale
        }

        pendingSelection = null
        return if (success) {
            SelectionCompletion.LatestSuccess
        } else {
            // The selectedName was optimistic and must not become "confirmed" in
            // the gap before Activity starts its authoritative reload.
            state = VpnServerUiState.Loading
            SelectionCompletion.LatestFailure
        }
    }

    /**
     * Record the user's pick as this group's preference.
     *
     * Also called for selections that arrived from the core (another client, or
     * a restored session) so the UI never argues with what is actually running.
     */
    fun rememberChoice(rawName: String) {
        preferredByGroup[ServerGroupingPolicy.keyOfRawName(rawName)] = rawName
    }

    /** Drop cached list so next ensure-load will fetch again. */
    fun invalidate() {
        invalidateInventory()
    }

    /**
     * Called when an in-flight load job is cancelled.
     * Unlocks the parallel-request guard and clears any probe progress without
     * allowing a cancelled job to start another probe inside the cooldown.
     */
    fun onRequestCancelled() {
        requestInFlight = false
        activeLatencyProbe?.let { finishHealthCheck(it) }
        if (state is VpnServerUiState.Loading) {
            // Leave Loading; caller may re-trigger ensure.
        }
    }

    private fun reapplyPendingSelection() {
        val selection = pendingSelection ?: return
        val ready = state as? VpnServerUiState.Ready ?: return
        if (ready.servers.none { it.name == selection.name }) return
        state = ready.copy(selectedName = selection.name)
        rememberChoice(selection.name)
    }

    private companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    }
}
