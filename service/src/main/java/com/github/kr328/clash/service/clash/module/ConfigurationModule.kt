package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import java.util.*

class ConfigurationModule(service: Service) :
    Module<ConfigurationModule.LoadException>(service), ConfigurationReloadPort {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)
    private val directRequests = ConfigurationReloadMailbox()

    // Non-conflated: each direct caller gets its own completion for its own
    // attempt, serialized below with the existing broadcast/CONFLATED reload
    // triggers rather than racing them.
    override suspend fun reloadAndAwait(): ConfigurationReloadResult = directRequests.reloadAndAwait()

    private sealed interface ReloadTrigger {
        data class Broadcast(val changed: UUID?) : ReloadTrigger
        data class Direct(val request: ConfigurationReloadMailbox.Request) : ReloadTrigger
    }

    override suspend fun run() {
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        try {
            while (true) {
                val trigger: ReloadTrigger = select {
                    broadcasts.onReceive {
                        ReloadTrigger.Broadcast(
                            if (it.action == Intents.ACTION_PROFILE_CHANGED)
                                UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                            else
                                null
                        )
                    }
                    reload.onReceive {
                        ReloadTrigger.Broadcast(null)
                    }
                    directRequests.onReceive {
                        ReloadTrigger.Direct(it)
                    }
                }

                // A direct request always forces a real reload of whatever is
                // currently active — it never carries a `changed` uuid, so the
                // staleness skip below never applies to it.
                val changed = (trigger as? ReloadTrigger.Broadcast)?.changed
                val request = (trigger as? ReloadTrigger.Direct)?.request

                try {
                    val current = store.activeProfile
                        ?: throw NullPointerException("No profile selected")

                    if (current == loaded && changed != null && changed != loaded)
                        continue

                    val active = ImportedDao().queryByUUID(current)
                        ?: throw NullPointerException("No profile selected")

                    Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                    Clash.load(service.importedDir.resolve(active.uuid.toString())).await()

                    // Past this point the core is serving `current`. Record it here, not
                    // after the steps below, so `loaded` can never lag the running core:
                    // a failure in selections/status/broadcast leaves the tunnel on the
                    // new configuration and must be reported as such.
                    loaded = current

                    val remove = SelectionDao().querySelections(active.uuid)
                        .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                        .map { it.proxy }

                    SelectionDao().removeSelections(active.uuid, remove)

                    StatusProvider.currentProfile = active.name

                    service.sendProfileLoaded(current)

                    Log.d("Profile ${active.name} loaded")

                    request?.complete(ConfigurationReloadResult.Loaded(current))
                } catch (e: CancellationException) {
                    // Fail the accepted request immediately; mailbox.close()
                    // also covers it defensively during outer teardown.
                    request?.fail(e)
                    throw e
                } catch (e: Exception) {
                    request?.complete(ConfigurationReloadResult.Failed(e.message ?: "Unknown"))

                    if (ConfigurationLoadPolicy.abortRuntime(hasSuccessfulLoad = loaded != null)) {
                        return enqueueEvent(LoadException(e.message ?: "Unknown"))
                    }
                    Log.w("Profile reload failed, core still on $loaded: ${e.message}")
                }
            }
        } finally {
            // Wakes accepted, queued and future direct callers exceptionally.
            directRequests.close()
        }
    }
}
