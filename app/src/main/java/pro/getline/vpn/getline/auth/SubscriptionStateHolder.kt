package pro.getline.vpn.getline.auth

/**
 * Activity-scoped owner for Subscription destination state.
 *
 * Survives tab switches within the same Activity. Does not own network or VPN.
 *
 * In-flight loads use a generation token so a cancelled job's [onRequestCancelled]
 * cannot clear a superseding request that already began.
 */
class SubscriptionStateHolder {
    var state: SubscriptionUiState = SubscriptionUiState.Loading
        private set

    /** True while a load/refresh request is in flight (blocks parallel refresh). */
    var requestInFlight: Boolean = false
        private set

    /**
     * Generation of the current in-flight request (if any).
     * Captured by the host at [beginInitialLoad]/[beginRefresh]/[beginLinkOnlyRefresh]
     * and passed back to [onRequestCancelled] / [applyLinkOnlyRefreshResult] /
     * [applyLoadResult] so superseded jobs are ignored.
     */
    var flightGeneration: Int = 0
        private set

    /**
     * Whether an automatic load should run when the Subscription tab is shown.
     * Ready/Empty keep last successful outcome; Failed waits for explicit retry;
     * SignedOut waits for sign-in; Loading with no in-flight request should fetch.
     */
    fun needsInitialLoad(): Boolean {
        if (requestInFlight) return false
        return when (state) {
            is SubscriptionUiState.Loading -> true
            is SubscriptionUiState.Ready,
            is SubscriptionUiState.Empty,
            is SubscriptionUiState.Failed,
            is SubscriptionUiState.SignedOut -> false
        }
    }

    /**
     * Begin initial full-screen load.
     * Returns false if already in flight or a stable result already exists
     * (tab re-entry must not re-fetch — use [beginRefresh] for manual retry).
     */
    fun beginInitialLoad(): Boolean {
        if (requestInFlight) return false
        if (state !is SubscriptionUiState.Loading) return false
        markInFlight()
        state = SubscriptionUiState.Loading
        return true
    }

    /**
     * Begin manual refresh. Keeps Ready card when present.
     * Returns false if a request is already in flight.
     *
     * @param supersede when true, abandons the previous in-flight generation
     *   (host must have cancelled the previous job). Used when replacing a
     *   session load after sign-in.
     */
    fun beginRefresh(supersede: Boolean = false): Boolean {
        if (requestInFlight && !supersede) return false
        markInFlight()
        when (val current = state) {
            is SubscriptionUiState.Ready ->
                state = current.copy(isRefreshing = true, transientError = false)
            is SubscriptionUiState.Empty,
            is SubscriptionUiState.Failed,
            is SubscriptionUiState.SignedOut,
            is SubscriptionUiState.Loading ->
                state = SubscriptionUiState.Loading
        }
        return true
    }

    fun applySignedOut(
        hasImportedProfile: Boolean,
        linkOnly: LinkOnlyPresentation? = null,
    ) {
        endFlight()
        state = SubscriptionUiState.SignedOut(
            hasImportedProfile = hasImportedProfile,
            linkOnly = linkOnly,
        )
    }

    /**
     * Begin silent refresh of a link-only (no session) card.
     * Keeps [SubscriptionUiState.SignedOut] so the card does not disappear into Loading.
     *
     * @param supersede when true, abandons the previous in-flight generation
     *   (host already cancelled that job). Double-tap still uses supersede=false.
     * @return flight generation to pass to result/cancel, or null if rejected.
     */
    fun beginLinkOnlyRefresh(supersede: Boolean = false): Int? {
        if (requestInFlight && !supersede) return null
        val current = state as? SubscriptionUiState.SignedOut ?: return null
        markInFlight()
        state = current.copy(isRefreshing = true, refreshFailed = false)
        return flightGeneration
    }

    /**
     * Apply result of a link-only config refresh.
     *
     * [linkOnly] null means the managed profile is **confirmed** missing/inactive
     * after a successful inventory read — the card is removed. Callers must not
     * pass null for transient profile-backend failures; pass the previous card.
     *
     * [generation] must match the value returned by [beginLinkOnlyRefresh]; stale
     * completions from superseded jobs are ignored.
     */
    fun applyLinkOnlyRefreshResult(
        linkOnly: LinkOnlyPresentation?,
        failed: Boolean,
        generation: Int = flightGeneration,
    ) {
        if (generation != flightGeneration) return
        requestInFlight = false
        val current = state as? SubscriptionUiState.SignedOut ?: return
        state = current.copy(
            linkOnly = linkOnly,
            isRefreshing = false,
            refreshFailed = failed,
        )
    }

    fun updateReadyCard(presentation: SubscriptionPresentation) {
        state = when (val current = state) {
            is SubscriptionUiState.Ready -> current.copy(subscription = presentation)
            // A successful config update may recover an initial local snapshot
            // failure or fill a just-repaired managed profile.
            SubscriptionUiState.Empty,
            SubscriptionUiState.Failed -> SubscriptionUiState.Ready(presentation)
            is SubscriptionUiState.Loading,
            is SubscriptionUiState.SignedOut -> current
        }
    }

    fun applyLoadResult(
        result: SubscriptionLoadResult,
        presentation: SubscriptionPresentation?,
        generation: Int = flightGeneration,
    ) {
        if (generation != flightGeneration) return
        // Capture before overwrite; Ready may be mid-refresh (isRefreshing=true).
        val previousReady = state as? SubscriptionUiState.Ready
        requestInFlight = false
        state = when (result) {
            is SubscriptionLoadResult.Success -> {
                if (presentation == null) {
                    SubscriptionUiState.Empty
                } else {
                    SubscriptionUiState.Ready(
                        subscription = presentation,
                        isRefreshing = false,
                        transientError = false,
                    )
                }
            }
            SubscriptionLoadResult.SignedOut -> {
                // Prefer applySignedOut(hasImportedProfile); this is a safe fallback.
                SubscriptionUiState.SignedOut(hasImportedProfile = false)
            }
            SubscriptionLoadResult.TransientFailure -> {
                if (previousReady != null) {
                    previousReady.copy(isRefreshing = false, transientError = true)
                } else {
                    SubscriptionUiState.Failed
                }
            }
        }
    }

    /** Force clear so next ensure-load will fetch again (e.g. after logout). */
    fun resetToLoading() {
        endFlight()
        state = SubscriptionUiState.Loading
    }

    /**
     * Drop session-bound UI after auth changes (sign-in / sign-out).
     * Caller must follow with a forced load or [applySignedOut].
     */
    fun invalidateSessionState() {
        endFlight()
        state = SubscriptionUiState.Loading
    }

    /**
     * Called when an in-flight load job is cancelled (tab teardown / supersede).
     * Unlocks parallel-request guard without inventing a new result.
     * No-op when [generation] is stale (a newer request already owns the flight).
     */
    fun onRequestCancelled(generation: Int = flightGeneration) {
        if (generation != flightGeneration) return
        if (!requestInFlight) return
        requestInFlight = false
        when (val current = state) {
            is SubscriptionUiState.Ready ->
                state = current.copy(isRefreshing = false)
            is SubscriptionUiState.SignedOut ->
                state = current.copy(isRefreshing = false)
            else -> Unit
        }
    }

    private fun markInFlight() {
        flightGeneration++
        requestInFlight = true
    }

    private fun endFlight() {
        flightGeneration++
        requestInFlight = false
    }
}
