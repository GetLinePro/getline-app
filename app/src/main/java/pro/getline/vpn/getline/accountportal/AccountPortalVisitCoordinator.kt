package pro.getline.vpn.getline.accountportal

/**
 * Tracks a single Custom Tab / browser visit so subscription refresh runs once
 * on a real return-to-app, not on tab switches or ordinary onStart.
 *
 * Flow:
 * - successful launch → [onLaunched]
 * - Activity.onStop after launch → [onHostStopped]
 * - next Activity.onStart with both flags → consume and request refresh
 */
data class AccountPortalVisitState(
    val launched: Boolean = false,
    val hostStoppedAfterLaunch: Boolean = false,
)

class AccountPortalVisitCoordinator {
    @Volatile
    private var state = AccountPortalVisitState()

    /** True while waiting for return from a successfully launched portal. */
    val isVisitPending: Boolean
        get() = state.launched

    fun snapshot(): AccountPortalVisitState = state

    /**
     * Whether a new portal open is allowed.
     * False while a prior successful launch has not been consumed by return lifecycle.
     */
    fun canLaunch(): Boolean = !state.launched

    /** Call only after a successful browser launch. */
    fun onLaunched() {
        synchronized(this) {
            state = AccountPortalVisitState(
                launched = true,
                hostStoppedAfterLaunch = false,
            )
        }
    }

    /** Call when launch failed so a subsequent tap can retry immediately. */
    fun onLaunchFailed() {
        clear()
    }

    fun onHostStopped() {
        synchronized(this) {
            if (state.launched && !state.hostStoppedAfterLaunch) {
                state = state.copy(hostStoppedAfterLaunch = true)
            }
        }
    }

    /**
     * @return true exactly once when returning from a launched portal visit
     * that had observed onStop (real leave to browser).
     */
    fun onHostStarted(): Boolean {
        synchronized(this) {
            val current = state
            if (current.launched && current.hostStoppedAfterLaunch) {
                state = AccountPortalVisitState()
                return true
            }
            return false
        }
    }

    /** Logout / account switch: drop pending return without refresh. */
    fun clear() {
        synchronized(this) {
            state = AccountPortalVisitState()
        }
    }
}
