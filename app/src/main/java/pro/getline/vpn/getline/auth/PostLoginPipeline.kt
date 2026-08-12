package pro.getline.vpn.getline.auth

/**
 * Owns the preferred-subscription step for one browser-auth attempt.
 *
 * Auth Tab and the exported package callback can both observe the same
 * established session. They may request the step independently, but only the
 * first caller that enters [Running] may execute it. This state is owned by the
 * onboarding main thread; it does not provide cross-thread synchronization.
 */
internal class PostLoginPipeline {
    enum class State {
        NotStarted,
        Deferred,
        Running,
        Terminal,
    }

    var state: State = State.NotStarted
        private set

    val isDeferred: Boolean
        get() = state == State.Deferred

    /** Starts a new browser-auth attempt. */
    fun reset() {
        check(state != State.Running) { "Cannot reset a running post-login pipeline" }
        state = State.NotStarted
    }

    /** Records that a sibling callback found a session while browser auth is busy. */
    fun defer() {
        if (state == State.NotStarted) {
            state = State.Deferred
        }
    }

    /**
     * Claims the post-login step. A deferred request is claimable after the
     * browser owner releases its busy guard.
     */
    fun tryStart(): Boolean = when (state) {
        State.NotStarted,
        State.Deferred,
        -> {
            state = State.Running
            true
        }
        State.Running,
        State.Terminal,
        -> false
    }

    /** Allows a late package callback after a deferred handoff lost its session. */
    fun clearDeferred() {
        if (state == State.Deferred) {
            state = State.NotStarted
        }
    }

    fun finish() {
        check(state == State.Running) { "Post-login pipeline is not running" }
        state = State.Terminal
    }
}
