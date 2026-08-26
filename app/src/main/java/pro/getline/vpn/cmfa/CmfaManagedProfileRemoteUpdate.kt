package pro.getline.vpn.cmfa

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.service.RemoteService
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.unwrap
import com.github.kr328.clash.util.unbindServiceSilent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineSubscriptionId
import java.util.UUID

/** Same budget as product reimport: [ProfileProcessor.update] is in-process fetch. */
private const val UPDATE_TIMEOUT_MS = 60_000L

/**
 * One-shot bind to [RemoteService]. Does not use [com.github.kr328.clash.remote.Remote],
 * which unbinds when the UI is invisible — the case this worker exists for.
 */
internal suspend fun updateImportedProfileSilently(
    context: Context,
    id: GetLineSubscriptionId,
    timeoutMs: Long = UPDATE_TIMEOUT_MS,
    bindService: (Context, ServiceConnection) -> Boolean = { app, connection ->
        app.bindService(RemoteService::class.intent, connection, Context.BIND_AUTO_CREATE)
    },
    unbindService: (Context, ServiceConnection) -> Unit = { app, connection ->
        app.unbindServiceSilent(connection)
    },
): ConfigUpdateResult {
    val uuid = runCatching { UUID.fromString(id.value) }.getOrNull()
        ?: return ConfigUpdateResult.Unavailable
    val app = context.applicationContext
    val connected = CompletableDeferred<IRemoteService>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder) {
            connected.complete(service.unwrap(IRemoteService::class))
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            if (!connected.isCompleted) {
                connected.completeExceptionally(IllegalStateException("disconnected"))
            }
        }
    }
    val accepted = try {
        bindService(app, connection)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return ConfigUpdateResult.Unavailable
    }
    if (!accepted) {
        unbindService(app, connection)
        return ConfigUpdateResult.Unavailable
    }
    try {
        val remote = withTimeout(timeoutMs) { connected.await() }
        return withTimeout(timeoutMs) {
            val profiles = remote.profile()
            val current = profiles.queryByUUID(uuid)
                ?: return@withTimeout ConfigUpdateResult.NotFound
            if (!current.imported || current.type == Profile.Type.File) {
                return@withTimeout ConfigUpdateResult.NotRefreshable
            }
            profiles.updateSilently(uuid)
            ConfigUpdateResult.Updated
        }
    } catch (_: TimeoutCancellationException) {
        return ConfigUpdateResult.Unavailable
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        return ConfigUpdateResult.Unavailable
    } finally {
        unbindService(app, connection)
    }
}
