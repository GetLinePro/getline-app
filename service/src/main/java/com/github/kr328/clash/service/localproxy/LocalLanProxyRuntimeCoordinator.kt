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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
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

    suspend fun enable(config: LocalLanProxyRuntimeConfig): LocalLanProxyRuntimeResult = mutex.withLock {
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
        } catch (e: Exception) {
            return@withLock if (mutationStarted) {
                withContext(NonCancellable + Dispatchers.IO) {
                    rollbackFailedEnable(transaction, e.message)
                }
            } else {
                LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.ApplyFailed, message = e.message)
            }
        }

        if (apply.loaded && apply.authOutcome == LocalLanProxyProbeOutcome.Authenticated) {
            state = RuntimeState.Active(transaction)
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
        val transaction = (state as? RuntimeState.Active)?.transaction
            ?: return@withLock LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled)

        withContext(NonCancellable + Dispatchers.IO) {
            clearSessionOverride()

            val reloadResult = runCatching { reloadPort.reloadAndAwait() }
            if (reloadResult.isFailure) {
                Log.w("LocalLanProxy disable reload failed: ${reloadResult.exceptionOrNull()?.message}")
            }

            // The result of this live probe is the source of truth, not the
            // reload outcome above (see plan Decisions): a config load
            // failure can leave the core on its last-good config, which may
            // still be the one with GetLine's listener bound.
            when (confirmGoneOrFailStop(transaction)) {
                true -> {
                    state = RuntimeState.Inactive
                    LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled)
                }
                false -> LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.SafetyStop)
            }
        }
    }

    /** Must run inside the NonCancellable section already established by [enable]. */
    private suspend fun rollbackFailedEnable(
        transaction: Transaction,
        reloadFailedMessage: String?,
    ): LocalLanProxyRuntimeResult {
        // Clearing the field only stops a *future* reload from reopening the
        // listener; it does not close one that already bound. Force a second
        // reload regardless of the first attempt's outcome, then confirm via
        // the disable-probe before reporting failure (see plan Decisions).
        clearSessionOverride()

        val cleanupReload = runCatching { reloadPort.reloadAndAwait() }
        if (cleanupReload.isFailure) {
            Log.w("LocalLanProxy rollback reload failed: ${cleanupReload.exceptionOrNull()?.message}")
        }

        return if (confirmGoneOrFailStop(transaction)) {
            state = RuntimeState.Inactive
            LocalLanProxyRuntimeResult(
                LocalLanProxyRuntimeResult.Status.ApplyFailed,
                message = reloadFailedMessage ?: "local proxy did not become reachable",
            )
        } else {
            LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.SafetyStop)
        }
    }

    /**
     * Confirms the transaction's credentials no longer authenticate against
     * its endpoint, or fail-stops `TunService` and returns false. Successful
     * auth means GetLine's own listener is still reachable — teardown
     * failed. A different service occupying the port is not attributed to
     * GetLine. An ambiguous result cannot positively prove the listener is
     * gone, so it fails closed the same as a confirmed-live listener (see
     * plan Decisions) — the coordinator-level address-loss exception to this
     * is future endpoint-monitor work, not manual enable/disable.
     */
    private fun confirmGoneOrFailStop(transaction: Transaction): Boolean {
        val outcome = LocalLanProxyProbe.classifyAuth(
            connect = { connectProtected(transaction.endpoint, transaction.port, PROBE_TIMEOUT_MS) },
            username = transaction.username,
            password = transaction.password,
        )

        return when (outcome) {
            LocalLanProxyProbeOutcome.Refused, LocalLanProxyProbeOutcome.OccupiedByOther -> true
            LocalLanProxyProbeOutcome.Authenticated, LocalLanProxyProbeOutcome.Ambiguous -> {
                Log.w("LocalLanProxy could not confirm teardown ($outcome); fail-stopping TunService")
                service.sendClashRequestStop()
                false
            }
        }
    }

    private fun connectProtected(
        endpoint: LocalLanProxyEndpoint,
        port: Int,
        timeoutMs: Int,
    ): LocalLanProxyConnectionOutcome {
        val socket = Socket()
        try {
            endpoint.network.bindSocket(socket)
        } catch (e: IOException) {
            socket.close()
            return LocalLanProxyConnectionOutcome.Ambiguous
        }
        if (!protect(socket)) {
            socket.close()
            return LocalLanProxyConnectionOutcome.Ambiguous
        }
        socket.soTimeout = timeoutMs

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

private fun Throwable.hasErrno(errno: Int): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ErrnoException && current.errno == errno) return true
        current = current.cause
    }
    return false
}
