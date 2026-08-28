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
    suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeOutcome
    suspend fun disable(): LocalLanProxyRuntimeOutcome

    /** The running session's projection, or inactive when there is no session. */
    suspend fun state(): LocalLanProxyRuntimeState
}

/**
 * What a command attempt produced. The distinction is not cosmetic: a runtime
 * that *answered* `VpnUnavailable` has told us there is no session and so no
 * listener, while a call that never arrived has told us nothing at all. Both
 * look the same to the user — Enable is not available — but only the first is
 * evidence that a departed owner's listener is gone, which is what
 * `reconcileOwner()` needs before discarding their credentials.
 *
 * It stays internal: the product API keeps one `VpnUnavailable`.
 */
internal sealed interface LocalLanProxyRuntimeOutcome {
    /** The runtime ran the command and this is what it said. */
    data class Answered(val result: LocalLanProxyRuntimeResult) : LocalLanProxyRuntimeOutcome

    /** The command could not be delivered: not bound, bind rejected, or the service died mid-call. */
    object TransportUnavailable : LocalLanProxyRuntimeOutcome
}

/**
 * Production wiring: the private AIDL adapter for commands, the
 * `StatusProvider` projection for state.
 *
 * A dead binder is not retried. Enable and disable are transactions with real
 * consequences on the far side; a caller that repeats one blindly may run it
 * twice. Every way of failing to talk to the runtime is reported as
 * [LocalLanProxyRuntimeOutcome.TransportUnavailable] rather than being dressed
 * up as an answer, and the next state read tells the truth about what
 * survived.
 */
internal class BinderLocalLanProxyRuntimeClient(
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
                )
            )
        }

    override suspend fun disable(): LocalLanProxyRuntimeOutcome = call { it.disable() }

    override suspend fun state(): LocalLanProxyRuntimeState = withContext(Dispatchers.IO) {
        statusClient.localLanProxyState()
    }

    private suspend fun call(
        block: suspend (com.github.kr328.clash.service.remote.ILocalLanProxyRuntime) -> LocalLanProxyRuntimeResult,
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
                withContext(Dispatchers.IO) { block(remote.localLanProxy()) }
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

    private companion object {
        const val BIND_TIMEOUT_MS = 5_000L
    }
}
