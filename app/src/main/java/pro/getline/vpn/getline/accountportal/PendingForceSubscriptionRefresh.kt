package pro.getline.vpn.getline.accountportal

/**
 * Queues a single force subscription refresh when [beginRefresh] is rejected
 * because a request is already in flight (e.g. return from account portal during
 * a manual Ready refresh).
 *
 * Call [mark] when a force load cannot start; call [consume] after the in-flight
 * request completes successfully to run one follow-up force load.
 */
class PendingForceSubscriptionRefresh {
    @Volatile
    private var pending = false

    val isPending: Boolean
        get() = pending

    fun mark() {
        pending = true
    }

    fun clear() {
        pending = false
    }

    /**
     * @return true once if a force refresh was queued; clears the flag.
     */
    fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
