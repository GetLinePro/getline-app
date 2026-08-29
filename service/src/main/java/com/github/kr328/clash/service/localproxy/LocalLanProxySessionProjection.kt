package com.github.kr328.clash.service.localproxy

/**
 * What one VPN session is willing to say about its local proxy, and when it
 * stops being allowed to say anything.
 *
 * The coordinator's transactions are not the only thing with a lifetime: a
 * binder command that entered [LocalLanProxyRuntimeCoordinator.enable] before
 * teardown holds its own reference and can finish *after* the session has been
 * closed. Its result is not wrong — the listener really did bind — but by then
 * the Clash runtime is going away and taking it with it, so publishing Active
 * would leave the app looking at an endpoint that no longer exists.
 *
 * So the session's last word is fixed at [close]: Inactive, and nothing
 * published afterwards can overwrite it. Keeping that rule here rather than at
 * each call site is what makes it hold on the path nobody tests by hand — the
 * one where the transaction wins the race.
 */
internal class LocalLanProxySessionProjection(
    private val sink: (LocalLanProxyRuntimeState) -> Unit,
) {
    /**
     * Guards the flag *and* the publish it decides, as one step. Checking a
     * volatile flag and then publishing is not enough: a publish that read
     * `closed == false` can still be overtaken by [close] and land its Active
     * after the session's Inactive, which is exactly the stale-listener
     * report this class exists to prevent. Reads of [isClosed] outside the
     * lock stay honest — a transaction refused a moment early is correct, and
     * one that slips through is caught here.
     */
    private val lock = Any()

    @Volatile
    private var closed = false

    /** True once the session is over. Also the answer to "may a new transaction start?". */
    val isClosed: Boolean
        get() = closed

    /** Session start: a fresh session inherits nothing from the one before it. */
    fun open() {
        synchronized(lock) {
            sink(LocalLanProxyRuntimeState.Inactive)
        }
    }

    fun publish(state: LocalLanProxyRuntimeState) {
        synchronized(lock) {
            if (closed) return

            sink(state)
        }
    }

    /** Session end. Idempotent, and the last publish that has any effect. */
    fun close() {
        synchronized(lock) {
            if (closed) return

            closed = true

            sink(LocalLanProxyRuntimeState.Inactive)
        }
    }
}
