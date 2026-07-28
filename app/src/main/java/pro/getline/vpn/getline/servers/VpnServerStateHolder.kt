package pro.getline.vpn.getline.servers

/**
 * Activity-scoped owner for Servers destination state.
 *
 * Survives tab switches within the same Activity. Does not call Clash or touch VPN.
 */
class VpnServerStateHolder {
    var state: VpnServerUiState = VpnServerUiState.Loading
        private set

    var requestInFlight: Boolean = false
        private set

    private var lastHealthCheckAt: Long? = null

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
        val last = lastHealthCheckAt ?: return true
        return nowMs - last >= HEALTH_CHECK_INTERVAL_MS
    }

    fun onHealthCheckStarted(nowMs: Long) {
        lastHealthCheckAt = nowMs
    }

    /** Force the next load to measure again (retry, profile change, VPN restart). */
    fun invalidateHealthCheck() {
        lastHealthCheckAt = null
    }

    /**
     * Whether an automatic load should run when the Servers tab is shown.
     * Ready keeps last list; Empty/Failed wait for explicit retry;
     * VpnStopped reloads when VPN is up again; Loading with no in-flight should fetch.
     */
    fun needsInitialLoad(): Boolean {
        if (requestInFlight) return false
        return when (state) {
            is VpnServerUiState.Loading,
            is VpnServerUiState.VpnStopped -> true
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
            is VpnServerUiState.Loading,
            is VpnServerUiState.VpnStopped -> Unit
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

    fun applyVpnStopped() {
        requestInFlight = false
        state = VpnServerUiState.VpnStopped
        // Delays measured through a dead tunnel are meaningless on restart.
        lastHealthCheckAt = null
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
    }

    /**
     * Optimistically mark [name] selected after a user tap.
     * No-op when not Ready or name unknown.
     */
    fun applySelected(name: String): Boolean {
        val ready = state as? VpnServerUiState.Ready ?: return false
        if (!ready.selectable) return false
        if (ready.servers.none { it.name == name }) return false
        state = ready.copy(selectedName = name)
        rememberChoice(name)
        return true
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
        requestInFlight = false
        state = VpnServerUiState.Loading
        lastHealthCheckAt = null
    }

    /**
     * Called when an in-flight load job is cancelled.
     * Unlocks parallel-request guard without inventing a new result.
     */
    fun onRequestCancelled() {
        if (!requestInFlight) return
        requestInFlight = false
        if (state is VpnServerUiState.Loading) {
            // Leave Loading; caller may re-trigger ensure.
        }
    }

    private companion object {
        const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    }
}
