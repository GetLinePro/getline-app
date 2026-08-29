package pro.getline.vpn.cmfa.localproxy

import android.content.Context
import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeState as ServiceRuntimeState
import com.github.kr328.clash.service.remote.ILocalLanProxyRuntime
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeConfig
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult as ServiceRuntimeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import pro.getline.vpn.getline.localproxy.LocalLanProxyRuntimeClient
import pro.getline.vpn.getline.localproxy.LocalLanProxyRuntimeOutcome
import pro.getline.vpn.getline.localproxy.LocalLanProxyRuntimeResult
import pro.getline.vpn.getline.localproxy.LocalLanProxyRuntimeState
import pro.getline.vpn.getline.localproxy.LocalLanProxyUserConfig

/**
 * CMFA/service adapter for the product-facing local-proxy runtime port.
 * Service/AIDL/status types terminate here and never reach the product facade.
 */
internal class CmfaLocalLanProxyRuntimeClient(
    context: Context,
) : LocalLanProxyRuntimeClient {
    private val statusClient = StatusClient(context.applicationContext)

    override suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeOutcome =
        call {
            it.enable(
                LocalLanProxyRuntimeConfig(
                    port = config.port,
                    username = config.username,
                    password = config.password,
                ),
            )
        }

    override suspend fun disable(): LocalLanProxyRuntimeOutcome = call { it.disable() }

    override suspend fun state(): LocalLanProxyRuntimeState = withContext(Dispatchers.IO) {
        when (val state = statusClient.localLanProxyState()) {
            ServiceRuntimeState.Inactive -> LocalLanProxyRuntimeState.Inactive
            is ServiceRuntimeState.Active -> LocalLanProxyRuntimeState.Active(state.address, state.port)
        }
    }

    private suspend fun call(
        block: suspend (ILocalLanProxyRuntime) -> ServiceRuntimeResult,
    ): LocalLanProxyRuntimeOutcome {
        return try {
            // Bounded: Resource.get() parks until the service connects, and an
            // app that is bound normally resolves it immediately. Waiting
            // forever would leave the screen spinning on a service that is not
            // coming, which reads as a hung transaction rather than as the
            // "no session" it actually is.
            val remote = withTimeoutOrNull(BIND_TIMEOUT_MS) { Remote.service.remote.get() }
                ?: return LocalLanProxyRuntimeOutcome.TransportUnavailable

            LocalLanProxyRuntimeOutcome.Answered(
                withContext(Dispatchers.IO) { block(remote.localLanProxy()) }.toProductResult(),
            )
        } catch (e: DeadObjectException) {
            Log.w("Local proxy runtime call lost the service", e)

            Remote.service.remote.peek()?.let { Remote.service.remote.reset(it) }

            LocalLanProxyRuntimeOutcome.TransportUnavailable
        } catch (e: IllegalStateException) {
            // bind rejected / not bound: the command did not reach a runtime,
            // which is not the same as a runtime saying it has no session.
            Log.w("Local proxy runtime unreachable: ${e.message}")

            LocalLanProxyRuntimeOutcome.TransportUnavailable
        }
    }

    private fun ServiceRuntimeResult.toProductResult(): LocalLanProxyRuntimeResult =
        LocalLanProxyRuntimeResult(
            status = when (status) {
                ServiceRuntimeResult.Status.Enabled -> LocalLanProxyRuntimeResult.Status.Enabled
                ServiceRuntimeResult.Status.Disabled -> LocalLanProxyRuntimeResult.Status.Disabled
                ServiceRuntimeResult.Status.VpnUnavailable ->
                    LocalLanProxyRuntimeResult.Status.VpnUnavailable
                ServiceRuntimeResult.Status.NoEligibleEndpoint ->
                    LocalLanProxyRuntimeResult.Status.NoEligibleEndpoint
                ServiceRuntimeResult.Status.PortOccupied ->
                    LocalLanProxyRuntimeResult.Status.PortOccupied
                ServiceRuntimeResult.Status.ApplyFailed -> LocalLanProxyRuntimeResult.Status.ApplyFailed
                ServiceRuntimeResult.Status.SafetyStop -> LocalLanProxyRuntimeResult.Status.SafetyStop
            },
            message = message,
        )

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
