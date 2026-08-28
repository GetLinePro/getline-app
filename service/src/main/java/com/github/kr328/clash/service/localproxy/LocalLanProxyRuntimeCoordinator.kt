package com.github.kr328.clash.service.localproxy

import android.app.Service
import android.system.ErrnoException
import android.system.OsConstants
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.service.clash.module.ConfigurationReloadPort
import com.github.kr328.clash.service.clash.module.ConfigurationReloadResult
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeConfig
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult
import com.github.kr328.clash.service.util.sendClashRequestStop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Sole owner of the local-proxy runtime: the typed Session override,
 * protected/network-bound occupancy and auth probes, the enable/disable state
 * machine, failed-enable rollback and the fail-stop decision (see plan
 * Module boundary and Decisions). `TunService` installs/connects this — via
 * [reloadPort], [endpointSource] and [protect] — but never decides its
 * policy, and neither `ConfigurationModule` nor any Activity participates in
 * the transaction below.
 *
 * It also owns the endpoint *policy*: [start] subscribes to
 * [LocalLanProxyEndpointSource.changes], and losing or changing the approved
 * address runs the very same disable transaction a user-requested disable
 * runs. The source only observes; nothing outside this class decides what an
 * endpoint change means.
 *
 * One coordinator instance lives for the lifetime of one running VPN/service
 * session; state is never persisted and resets when the instance is
 * discarded.
 */
class LocalLanProxyRuntimeCoordinator(
    private val service: Service,
    private val reloadPort: ConfigurationReloadPort,
    private val endpointSource: LocalLanProxyEndpointSource,
    private val protect: (Socket) -> Boolean,
) {
    private data class Transaction(
        val endpoint: LocalLanProxyEndpoint,
        val port: Int,
        val username: String,
        val password: String,
    )

    private sealed interface RuntimeState {
        object Inactive : RuntimeState
        data class Active(val transaction: Transaction) : RuntimeState
    }

    private val mutex = Mutex()
    private var state: RuntimeState = RuntimeState.Inactive

    // Bounded by close(), which TunService calls from its runtime teardown.
    // The transaction bodies below are NonCancellable, so cancelling this
    // scope can never interrupt a cleanup half-way.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Owns both halves of the session boundary: what the rest of the system is
     * told, and whether this session is still open for business. One flag, one
     * owner — an `enable` arriving during teardown and a projection published
     * during teardown are the same question asked twice.
     */
    private val projection = LocalLanProxySessionProjection { state ->
        LocalLanProxyRuntimeRegistry.publish(service, state)
    }

    /** Begins reacting to endpoint changes. Call once, before any enable. */
    fun start() {
        // Session boundary. The :background process outlives a single VPN
        // session, so a fresh coordinator must not inherit whatever the
        // previous one published — normally already Inactive, but not after a
        // session that ended while a listener could not be accounted for.
        projection.open()

        scope.launch {
            endpointSource.changes.collect {
                try {
                    onEndpointChanged()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Last resort, and the reason it exists: an exception
                    // escaping here would both kill the only observer — leaving
                    // a possibly-live listener with nothing watching its
                    // address — and surface as an uncaught failure in the
                    // :background process. Neither is an acceptable way to
                    // learn that teardown could not be accounted for.
                    Log.e("LocalLanProxy endpoint observer failed unexpectedly", e)
                    failStopIfActive()
                }
            }
        }
    }

    /**
     * Stops reacting to endpoint changes and makes a *bounded, best-effort*
     * attempt to let an in-flight automatic transaction finish first. Does not
     * disable an active proxy: the runtime is going away with the whole Clash
     * session, which drops the Session override and its listener along with it.
     *
     * The wait exists because [disableActive] runs `NonCancellable`, so
     * cancelling the scope does not stop an in-flight reload or probe;
     * returning immediately would leave one running against a
     * `ConfigurationModule` and Clash runtime that `TunService` is already
     * tearing down. In practice the reload port completes pending requests
     * exceptionally on shutdown, so this returns in milliseconds.
     *
     * It is not a guarantee. On timeout this logs and returns, and the
     * transaction may still be running while teardown proceeds. What that can
     * no longer do is mislead anyone: the projection is already closed, so a
     * late transaction's state is dropped rather than published. Making the
     * wait itself airtight would mean giving teardown a real completion
     * handshake with the reload port rather than a deadline; that is
     * deliberately not done here.
     */
    suspend fun close() {
        // Before the wait, not after: this both fixes the session's last word
        // and refuses any transaction that has not started yet. A command
        // already inside enable() is the case the projection exists for — it
        // may finish, and its Active is dropped rather than published.
        projection.close()

        val job = scope.coroutineContext[Job] ?: return
        job.cancel()

        if (withTimeoutOrNull(CLOSE_TIMEOUT_MS) { job.join() } == null) {
            Log.w("LocalLanProxy observer did not settle within ${CLOSE_TIMEOUT_MS}ms of close")
        }
    }

    /**
     * Fail-stop requested from a path that has no result to return. Never
     * throws: it is called from failure handling, where a second exception
     * would defeat the purpose.
     */
    private fun requestFailStop() {
        runCatching { service.sendClashRequestStop() }
            .onFailure { Log.e("LocalLanProxy fail-stop request failed", it) }
    }

    private suspend fun failStopIfActive() {
        val active = mutex.withLock { state is RuntimeState.Active }
        if (!active) return

        Log.w("LocalLanProxy cannot account for an active listener; fail-stopping TunService")
        requestFailStop()
    }

    /**
     * The only writer of [state], so the registry cannot drift from the
     * transaction it is supposed to mirror. Every caller already holds
     * [mutex]; publishing under it keeps the published order equal to the
     * transaction order, and the broadcast it triggers is asynchronous, so
     * nothing waits on a reader while the lock is held.
     */
    private fun transitionTo(next: RuntimeState) {
        state = next

        projection.publish(next.asProjection())
    }

    /**
     * What leaves the process. The `Network` and the credentials stay behind:
     * a reader gets the address a LAN client dials and nothing it has no use
     * for.
     */
    private fun RuntimeState.asProjection(): LocalLanProxyRuntimeState = when (this) {
        RuntimeState.Inactive -> LocalLanProxyRuntimeState.Inactive
        is RuntimeState.Active -> LocalLanProxyRuntimeState.Active(
            address = transaction.endpoint.address.literal(),
            port = transaction.port,
        )
    }

    suspend fun enable(config: LocalLanProxyRuntimeConfig): LocalLanProxyRuntimeResult = mutex.withLock {
        // A binder caller can hold this coordinator across teardown: the
        // manager resolved it before TunService cleared the handle. Starting a
        // transaction now would bind a listener into a Clash runtime that is
        // already stopping.
        if (projection.isClosed) {
            return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)
        }

        if (state is RuntimeState.Active) {
            return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Enabled)
        }

        val endpoint = endpointSource.currentEndpoint()
            ?: return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.NoEligibleEndpoint)

        // Preflight: reject an already-running proxy with manually reused
        // credentials before mutating anything — see plan Decisions. Any
        // accepted connection is PortOccupied; only explicit refusal permits
        // proceeding, everything else (timeout, ambiguous routing) fails
        // closed without touching the override.
        val preflight = withContext(Dispatchers.IO) {
            LocalLanProxyProbe.classifyPreflight { connectProtected(endpoint, config.port, PREFLIGHT_TIMEOUT_MS) }
        }
        when (preflight) {
            LocalLanProxyPreflightOutcome.Occupied ->
                return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.PortOccupied)
            LocalLanProxyPreflightOutcome.Ambiguous ->
                return@withLock LocalLanProxyRuntimeResult(
                    LocalLanProxyRuntimeResult.Status.ApplyFailed,
                    message = "preflight could not confirm the port was free",
                )
            LocalLanProxyPreflightOutcome.Free -> Unit
        }

        val transaction = Transaction(endpoint, config.port, config.username, config.password)

        var mutationStarted = false
        val apply = try {
            withContext(Dispatchers.IO) {
                // Mark the transaction before patching: if patchOverride throws
                // after changing native state, cancellation/error cleanup must
                // still clear and reload it.
                mutationStarted = true
                setSessionOverride(transaction)

                val reloadResult = reloadCatchingCancellation()
                val loaded = reloadResult.getOrNull() is ConfigurationReloadResult.Loaded
                val authOutcome = if (loaded) {
                    LocalLanProxyProbe.classifyAuth(
                        connect = { connectProtected(endpoint, config.port, PROBE_TIMEOUT_MS) },
                        username = config.username,
                        password = config.password,
                    )
                } else {
                    null
                }
                ApplyAttempt(reloadResult, loaded, authOutcome)
            }
        } catch (e: CancellationException) {
            // Applying stays cancellable. Only the compensating transaction is
            // shielded, so a caller that observes cancellation cannot leave its
            // credentials authenticating on the LAN endpoint.
            if (mutationStarted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    rollbackFailedEnable(transaction, e.message)
                }
            }
            throw e
        } catch (e: Throwable) {
            // Throwable, not Exception: setSessionOverride and the reload go
            // through JNI, and an Error (UnsatisfiedLinkError, and friends)
            // escaping here with mutationStarted = true would skip rollback
            // entirely and leave a bound listener behind.
            return@withLock if (mutationStarted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    rollbackFailedEnable(transaction, e.message)
                }
            } else {
                LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.ApplyFailed, message = e.message)
            }
        }

        if (apply.loaded && apply.authOutcome == LocalLanProxyProbeOutcome.Authenticated) {
            transitionTo(RuntimeState.Active(transaction))
            LocalLanProxyRuntimeResult(
                status = LocalLanProxyRuntimeResult.Status.Enabled,
                endpointAddress = endpoint.address.hostAddress,
                endpointPort = transaction.port,
            )
        } else {
            withContext(NonCancellable + Dispatchers.IO) {
                rollbackFailedEnable(transaction, apply.failureMessage())
            }
        }
    }

    suspend fun disable(): LocalLanProxyRuntimeResult = mutex.withLock {
        if (projection.isClosed) {
            return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)
        }

        val transaction = (state as? RuntimeState.Active)?.transaction
            ?: return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled)

        disableActive(transaction, reason = "request")
    }

    /**
     * The disable transaction itself. Must be called with [mutex] held and an
     * [RuntimeState.Active] [transaction]. Both the user-requested disable and
     * the endpoint-change disable go through here, so there is exactly one
     * teardown path to reason about.
     */
    private suspend fun disableActive(
        transaction: Transaction,
        reason: String,
    ): LocalLanProxyRuntimeResult = withContext(NonCancellable + Dispatchers.IO) {
        try {
            clearSessionOverride()

            val reloadResult = runCatching { reloadPort.reloadAndAwait() }
            val reloadLoaded = reloadResult.getOrNull() is ConfigurationReloadResult.Loaded
            if (!reloadLoaded) {
                Log.w("LocalLanProxy disable ($reason) reload unconfirmed: ${describeReloadFailure(reloadResult)}")
            }

            when (decideTeardown(reloadLoaded, transaction)) {
                LocalLanProxyTeardownOutcome.ConfirmedGone -> {
                    transitionTo(RuntimeState.Inactive)
                    LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled)
                }
                LocalLanProxyTeardownOutcome.FailStop -> {
                    Log.w("LocalLanProxy could not confirm teardown ($reason); fail-stopping TunService")
                    requestFailStop()
                    LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.SafetyStop)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // clearSessionOverride, the native override calls and the probe can
            // all throw. A teardown that ends in an exception has confirmed
            // nothing, so it takes the same route an unconfirmed reload takes
            // instead of escaping — to a binder caller on the requested path,
            // or to a coroutine with nobody to catch it on the automatic one.
            Log.e("LocalLanProxy disable ($reason) failed unexpectedly; fail-stopping TunService", e)
            requestFailStop()
            LocalLanProxyRuntimeResult(
                LocalLanProxyRuntimeResult.Status.SafetyStop,
                message = e.message,
            )
        }
    }

    /**
     * Endpoint policy. Runs on every change signal while a transaction is
     * active.
     *
     * The distinction that matters is address versus `Network`. Mihomo's
     * listener is bound to the *address*, so a Wi-Fi reconnect that keeps the
     * same DHCP lease leaves it serving normally even though Android has
     * handed out a fresh [android.net.Network]. Tearing down there would
     * disable the proxy on every brief Wi-Fi blip; worse, probing through the
     * dead old `Network` fails with an ambiguous error against an address that
     * is still local, which is a fail-stop of the entire VPN. So an unchanged
     * address only refreshes the probe route.
     *
     * A changed or vanished address is the real trigger, and it runs the same
     * transaction [disable] runs.
     *
     * An address that *returned* is the third case, and it cannot be told from
     * the first by inspection: a hotspot restart reuses its address, so all
     * that is left to see is a new `Network` — exactly what a harmless
     * reconnect looks like. [LocalLanProxyEndpoint.epoch] carries the fact
     * that the source watched it disappear. When it moves, the listener died
     * with the address and this disables, but it adopts the *current* route
     * first: probing a returned address through the dead `Network` it was
     * approved on is precisely the ambiguous-against-still-local combination
     * that fail-stops the VPN, and here the live route is already known.
     */
    private suspend fun onEndpointChanged() {
        mutex.withLock {
            if (projection.isClosed) return@withLock

            val transaction = (state as? RuntimeState.Active)?.transaction ?: return@withLock

            val current = try {
                endpointSource.currentEndpoint()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Cannot tell whether the approved address is still ours while
                // a credentialed listener is bound to it. Treating that as
                // "nothing changed" would leave it serving unwatched.
                Log.e("LocalLanProxy endpoint query failed while active", e)
                requestFailStop()
                return@withLock
            }

            if (current != null && current.address == transaction.endpoint.address) {
                if (current.epoch == transaction.endpoint.epoch) {
                    if (current.network != transaction.endpoint.network) {
                        transitionTo(RuntimeState.Active(transaction.copy(endpoint = current)))
                        Log.i("LocalLanProxy endpoint kept address, refreshed probe network")
                    }

                    return@withLock
                }

                Log.i("LocalLanProxy approved address returned after a loss; disabling")

                val returned = transaction.copy(endpoint = current)
                transitionTo(RuntimeState.Active(returned))

                val loss = disableActive(returned, reason = "endpoint-loss")
                Log.i("LocalLanProxy automatic disable result: ${loss.status}")

                return@withLock
            }

            Log.i("LocalLanProxy approved endpoint no longer eligible; disabling")

            val result = disableActive(transaction, reason = "endpoint-change")
            Log.i("LocalLanProxy automatic disable result: ${result.status}")
        }
    }

    /** Must run inside the NonCancellable section already established by [enable]. */
    private suspend fun rollbackFailedEnable(
        transaction: Transaction,
        reloadFailedMessage: String?,
    ): LocalLanProxyRuntimeResult {
        return try {
            // Clearing the field only stops a *future* reload from reopening
            // the listener; it does not close one that already bound. Force a
            // second reload regardless of the first attempt's outcome, then
            // confirm via the disable-probe before reporting failure (see plan
            // Decisions).
            clearSessionOverride()

            val cleanupReload = runCatching { reloadPort.reloadAndAwait() }
            val cleanupLoaded = cleanupReload.getOrNull() is ConfigurationReloadResult.Loaded
            if (!cleanupLoaded) {
                Log.w("LocalLanProxy rollback reload unconfirmed: ${describeReloadFailure(cleanupReload)}")
            }

            when (decideTeardown(cleanupLoaded, transaction)) {
                LocalLanProxyTeardownOutcome.ConfirmedGone -> {
                    transitionTo(RuntimeState.Inactive)
                    LocalLanProxyRuntimeResult(
                        LocalLanProxyRuntimeResult.Status.ApplyFailed,
                        message = reloadFailedMessage ?: "local proxy did not become reachable",
                    )
                }
                LocalLanProxyTeardownOutcome.FailStop -> {
                    Log.w("LocalLanProxy could not confirm teardown; fail-stopping TunService")
                    requestFailStop()
                    LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.SafetyStop)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // The same boundary disableActive() has, and for a sharper reason.
            // A throw from clearSessionOverride() or the teardown probe would
            // escape enable() while `state` is still Inactive — it is only set
            // to Active on a *successful* enable — so nothing here would fail
            // -stop, and a later disable() would short-circuit to Disabled
            // against a listener that may well have bound before the failure.
            Log.e("LocalLanProxy rollback failed unexpectedly; fail-stopping TunService", e)
            requestFailStop()
            LocalLanProxyRuntimeResult(
                LocalLanProxyRuntimeResult.Status.SafetyStop,
                message = e.message,
            )
        }
    }

    /**
     * [LocalLanProxyTeardownPolicy.decide] wired to this coordinator's real
     * probe. [reloadLoaded] must reflect only a *confirmed*
     * [ConfigurationReloadResult.Loaded] — see the policy's kdoc for why an
     * unconfirmed reload fails closed unconditionally, without even running
     * the probe (a still in-flight, orphaned apply from an earlier request
     * can complete later and reopen the listener after this call returns),
     * and why an address the phone no longer holds lets an otherwise ambiguous
     * probe still count as gone.
     */
    private fun decideTeardown(reloadLoaded: Boolean, transaction: Transaction): LocalLanProxyTeardownOutcome {
        // Read locality at decision time, alongside the probe, so the two
        // describe the same instant.
        val addressStillLocal = LocalLanProxyAddressLocality.isAssignedLocally(transaction.endpoint.address)

        return LocalLanProxyTeardownPolicy.decide(reloadLoaded, addressStillLocal) {
            LocalLanProxyProbe.classifyAuth(
                connect = { connectProtected(transaction.endpoint, transaction.port, PROBE_TIMEOUT_MS) },
                username = transaction.username,
                password = transaction.password,
            )
        }
    }

    private fun describeReloadFailure(reloadResult: Result<ConfigurationReloadResult>): String? =
        (reloadResult.getOrNull() as? ConfigurationReloadResult.Failed)?.message
            ?: reloadResult.exceptionOrNull()?.message

    private fun connectProtected(
        endpoint: LocalLanProxyEndpoint,
        port: Int,
        timeoutMs: Int,
    ): LocalLanProxyConnectionOutcome {
        val socket = Socket()
        var setUp = false
        try {
            endpoint.network.bindSocket(socket)
            if (!protect(socket)) {
                return LocalLanProxyConnectionOutcome.Ambiguous
            }
            socket.soTimeout = timeoutMs
            setUp = true
        } catch (e: Exception) {
            return LocalLanProxyConnectionOutcome.Ambiguous
        } finally {
            // Any failure above (bindSocket, protect() itself throwing rather
            // than returning false, or the soTimeout setter) must not leak
            // the fd — close on every path that doesn't reach setUp = true.
            if (!setUp) socket.close()
        }

        return try {
            socket.connect(InetSocketAddress(endpoint.address, port), timeoutMs)
            LocalLanProxyConnectionOutcome.Connected(socket)
        } catch (e: ConnectException) {
            socket.close()
            if (e.hasErrno(OsConstants.ECONNREFUSED)) {
                LocalLanProxyConnectionOutcome.Refused
            } else {
                LocalLanProxyConnectionOutcome.Ambiguous
            }
        } catch (e: IOException) {
            socket.close()
            LocalLanProxyConnectionOutcome.Ambiguous
        }
    }

    private suspend fun reloadCatchingCancellation(): Result<ConfigurationReloadResult> = try {
        Result.success(reloadPort.reloadAndAwait())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun setSessionOverride(transaction: Transaction) {
        val address = transaction.endpoint.address.hostAddress
            ?: throw IOException("endpoint has no textual address")

        val configuration = Clash.queryOverride(Clash.OverrideSlot.Session)
        configuration.localProxy = ConfigurationOverride.LocalProxy(
            listen = address,
            port = transaction.port,
            username = transaction.username,
            password = transaction.password,
        )
        Clash.patchOverride(Clash.OverrideSlot.Session, configuration)
    }

    private fun clearSessionOverride() {
        val configuration = Clash.queryOverride(Clash.OverrideSlot.Session)
        if (configuration.localProxy == null) return
        configuration.localProxy = null
        Clash.patchOverride(Clash.OverrideSlot.Session, configuration)
    }

    companion object {
        private const val PREFLIGHT_TIMEOUT_MS = 1_000
        private const val PROBE_TIMEOUT_MS = 2_000
        private const val CLOSE_TIMEOUT_MS = 5_000L
    }

    private data class ApplyAttempt(
        val reloadResult: Result<ConfigurationReloadResult>,
        val loaded: Boolean,
        val authOutcome: LocalLanProxyProbeOutcome?,
    ) {
        fun failureMessage(): String? =
            (reloadResult.getOrNull() as? ConfigurationReloadResult.Failed)?.message
                ?: reloadResult.exceptionOrNull()?.message
    }
}

/**
 * `getHostAddress` is platform-nullable; for an [Inet4Address] that
 * was resolved from an interface it never is. The fallback stays truthful
 * rather than guessing — `toString` renders as `[host]/1.2.3.4` — because the
 * alternative, reporting inactive while a listener is bound, is the direction
 * that hides a live endpoint from its owner.
 */
private fun Inet4Address.literal(): String =
    hostAddress ?: toString().substringAfterLast('/')

private fun Throwable.hasErrno(errno: Int): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException && current.errno == errno) return true
        current = current.cause
    }
    return false
}
