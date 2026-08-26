package pro.getline.vpn.getline.servers

/**
 * Activity-scoped owner for Servers destination state.
 *
 * Survives tab switches within the same Activity. Does not call Clash or touch VPN.
 */
class VpnServerStateHolder {
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

    /** True while a request (from any [beginInitialLoad]/[beginRefresh]/[beginReconcile]) owns the guard. */
    val requestInFlight: Boolean
        get() = activeRequestToken != null

    /** Whether a real latency probe is in flight. The UI has no use for finer states. */
    var isProbing: Boolean = false
        private set

    private var lastHealthCheckAt: Long? = null
    private var nextProbeToken = 0L
    private var activeProbeToken: Long? = null

    private var nextRequestToken = 0L
    private var activeRequestToken: Long? = null

    /**
     * Set when an authoritative reload (profile change, VPN restart) is rejected
     * because a probe from an earlier load is still running. The probe is left
     * to finish undisturbed — cancelling it would risk it never completing under
     * frequent reloads — and [consumePendingReload] is checked once it does, so
     * the reload this flag represents is deferred, never dropped.
     */
    private var pendingReload = false

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
        if (isProbing) return false
        val last = lastHealthCheckAt ?: return true
        return nowMs - last >= HEALTH_CHECK_INTERVAL_MS
    }

    /**
     * Claim the next health check, if eligible.
     *
     * The returned token identifies this probe: only the [finishHealthCheck] call
     * carrying the same token can end it, so a cancelled/stale load job can never
     * finish a newer job's still-running probe.
     */
    fun beginHealthCheck(nowMs: Long): Long? {
        if (!shouldHealthCheck(nowMs)) return null

        val token = ++nextProbeToken
        lastHealthCheckAt = nowMs
        activeProbeToken = token
        isProbing = true
        return token
    }

    /** Finish only if [token] still owns the in-flight probe. */
    fun finishHealthCheck(token: Long): Boolean {
        if (activeProbeToken != token) return false
        activeProbeToken = null
        isProbing = false
        return true
    }

    /** Force the next eligible load to measure again. */
    fun invalidateHealthCheck() {
        lastHealthCheckAt = null
    }

    /** Invalidate the server inventory without changing health-check eligibility. */
    fun invalidateInventory() {
        activeRequestToken = null
        pendingSelection = null
        state = VpnServerUiState.Loading
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
     * A load or its trailing health-check probe is busy. [requestInFlight] alone
     * is not enough: [applyLoadResult] clears it as soon as the group list is
     * in, before the probe that follows it runs. Without also checking
     * [isProbing] here, a reconcile arriving mid-probe would pass this guard
     * and cancel/restart the job that owns it, so a probe could never survive
     * long enough to finish.
     */
    private val isBusy: Boolean
        get() = requestInFlight || isProbing

    /**
     * Begin initial full-screen load.
     *
     * Returns the new request's token, or null if already busy or a stable
     * non-reload state already exists. The caller must hand this token to
     * [onRequestCancelled] if its job is cancelled — see that function.
     */
    fun beginInitialLoad(): Long? {
        if (isBusy) return null
        when (state) {
            is VpnServerUiState.Loading -> Unit
            else -> return null
        }
        state = VpnServerUiState.Loading
        return newRequestToken()
    }

    /**
     * Begin forced reload (retry, profile change, Clash start while on tab).
     *
     * Returns the new request's token, or null if already busy. If busy
     * specifically because a probe is running, queues this reload for
     * [consumePendingReload] to pick up once that probe finishes, instead of
     * losing it: the probe may be measuring against inventory this reload is
     * about to replace.
     */
    fun beginRefresh(): Long? {
        if (isBusy) {
            if (isProbing) pendingReload = true
            return null
        }
        state = VpnServerUiState.Loading
        return newRequestToken()
    }

    /**
     * Soft re-query after Activity resume.
     *
     * Keeps a [VpnServerUiState.Ready] list visible (no full-screen loading flash)
     * while still re-reading Mihomo — external ProxyActivity selection emits no
     * profile/Clash lifecycle event.
     *
     * Non-Ready states go to Loading. Returns the new request's token, or null
     * if already busy — see [beginRefresh] for the queueing behavior while a
     * probe is running.
     */
    fun beginReconcile(): Long? {
        if (isBusy) {
            if (isProbing) pendingReload = true
            return null
        }
        when (state) {
            is VpnServerUiState.Ready -> Unit
            else -> state = VpnServerUiState.Loading
        }
        return newRequestToken()
    }

    private fun newRequestToken(): Long {
        val token = ++nextRequestToken
        activeRequestToken = token
        return token
    }

    /**
     * Consume a reload that arrived while a probe from an earlier request was
     * still running. Called once that probe finishes.
     */
    fun consumePendingReload(): Boolean {
        val had = pendingReload
        pendingReload = false
        return had
    }

    /** Drop live latency after the tunnel stops; the inventory stays. */
    fun clearLiveDelays() {
        lastHealthCheckAt = null
        activeProbeToken = null
        isProbing = false
        // The VPN stopping supersedes any reload queued while the now-abandoned
        // probe was running; the ClashStop/ServiceRecreated handler decides the
        // reload from scratch.
        pendingReload = false
        val ready = state as? VpnServerUiState.Ready ?: return
        if (ready.servers.none { it.delayMs != null }) return
        state = ready.copy(servers = ready.servers.map { it.copy(delayMs = null) })
    }

    fun applyLoadResult(result: VpnServerLoadResult) {
        activeRequestToken = null
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

    /**
     * Force-clear the request guard regardless of ownership.
     *
     * For the VPN-stopping paths (ClashStop/ServiceRecreated), which abandon
     * whatever load is in flight outright rather than superseding it with a
     * specific newer one — see [onRequestCancelled] (with a token) for that
     * ownership-checked case.
     */
    fun onRequestCancelled() {
        activeRequestToken = null
    }

    /**
     * Called when the load job holding [token] is cancelled.
     *
     * Clears the guard only if [token] still owns it — the same ownership
     * problem [finishHealthCheck] solves for probes, but for the load itself:
     * a job can cancel *itself* to make way for a reload it had deferred (see
     * [consumePendingReload]), and by the time its own cleanup runs the new
     * job may already have claimed the guard with its own token. Never
     * touches probe state either, for the same reason — see [finishHealthCheck].
     */
    fun onRequestCancelled(token: Long) {
        if (activeRequestToken != token) return
        activeRequestToken = null
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
