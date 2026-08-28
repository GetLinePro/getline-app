package pro.getline.vpn.getline.localproxy

import android.content.Context
import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeState
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeConfig
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Everything the facade needs from the service side, behind one seam so the
 * facade's own logic can be tested without a bound Binder or a
 * `ContentProvider`.
 *
 * This is the only place in the app that speaks the private runtime
 * vocabulary; product code above [LocalLanProxyFacade] never sees these types.
 */
internal interface LocalLanProxyRuntimeClient {
    suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeResult
    suspend fun disable(): LocalLanProxyRuntimeResult

    /** The running session's projection, or inactive when there is no session. */
    suspend fun state(): LocalLanProxyRuntimeState
}

/**
 * Production wiring: the private AIDL adapter for commands, the
 * `StatusProvider` projection for state.
 *
 * A dead binder is not retried. Enable and disable are transactions with real
 * consequences on the far side; a caller that repeats one blindly may run it
 * twice. The service having died mid-call is reported as a failed apply, and
 * the next state read — the process is gone, so the projection is inactive —
 * tells the truth about what survived.
 */
internal class BinderLocalLanProxyRuntimeClient(
    context: Context,
) : LocalLanProxyRuntimeClient {
    private val statusClient = StatusClient(context.applicationContext)

    override suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeResult =
        call {
            it.enable(
                LocalLanProxyRuntimeConfig(
                    port = config.port,
                    username = config.username,
                    password = config.password,
                )
            )
        }

    override suspend fun disable(): LocalLanProxyRuntimeResult = call { it.disable() }

    override suspend fun state(): LocalLanProxyRuntimeState = withContext(Dispatchers.IO) {
        statusClient.localLanProxyState()
    }

    private suspend fun call(
        block: suspend (com.github.kr328.clash.service.remote.ILocalLanProxyRuntime) -> LocalLanProxyRuntimeResult,
    ): LocalLanProxyRuntimeResult {
        return try {
            // Bounded: Resource.get() parks until the service connects, and an
            // app that is bound normally resolves it immediately. Waiting
            // forever would leave the screen spinning on a service that is not
            // coming, which reads as a hung transaction rather than as the
            // "no session" it actually is.
            val remote = withTimeoutOrNull(BIND_TIMEOUT_MS) { Remote.service.remote.get() }
                ?: return LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)

            withContext(Dispatchers.IO) { block(remote.localLanProxy()) }
        } catch (e: DeadObjectException) {
            Log.w("Local proxy runtime call lost the service", e)

            Remote.service.remote.peek()?.let { Remote.service.remote.reset(it) }

            LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.ApplyFailed)
        } catch (e: IllegalStateException) {
            // bind rejected / not bound: there is no session to talk to.
            Log.w("Local proxy runtime unavailable: ${e.message}")

            LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)
        }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
