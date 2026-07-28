package pro.getline.vpn.getline.auth

/**
 * Activity-scoped owner for Subscription destination state.
 *
 * Survives tab switches within the same Activity. Does not own network or VPN.
 */
class SubscriptionStateHolder {
    var state: SubscriptionUiState = SubscriptionUiState.Loading
        private set

    /** True while a load/refresh request is in flight (blocks parallel refresh). */
    var requestInFlight: Boolean = false
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
        requestInFlight = true
        state = SubscriptionUiState.Loading
        return true
    }

    /**
     * Begin manual refresh. Keeps Ready card when present.
     * Returns false if a request is already in flight.
     */
    fun beginRefresh(): Boolean {
        if (requestInFlight) return false
        requestInFlight = true
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

    fun applySignedOut(hasImportedProfile: Boolean) {
        requestInFlight = false
        state = SubscriptionUiState.SignedOut(hasImportedProfile)
    }

    fun applyLoadResult(result: SubscriptionLoadResult, presentation: SubscriptionPresentation?) {
        // Capture before overwrite; Ready may be mid-refresh (isRefreshing=true).
        val previousReady = state as? SubscriptionUiState.Ready
        requestInFlight = false
        state = when (result) {
            is SubscriptionLoadResult.Success -> {
                if (result.preferred == null || presentation == null) {
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
        requestInFlight = false
        state = SubscriptionUiState.Loading
    }

    /**
     * Drop session-bound UI after auth changes (sign-in / sign-out).
     * Caller must follow with a forced load or [applySignedOut].
     */
    fun invalidateSessionState() {
        requestInFlight = false
        state = SubscriptionUiState.Loading
    }

    /**
     * Called when an in-flight load job is cancelled (tab teardown / supersede).
     * Unlocks parallel-request guard without inventing a new result.
     */
    fun onRequestCancelled() {
        if (!requestInFlight) return
        requestInFlight = false
        when (val current = state) {
            is SubscriptionUiState.Ready ->
                state = current.copy(isRefreshing = false)
            else -> Unit
        }
    }
}
