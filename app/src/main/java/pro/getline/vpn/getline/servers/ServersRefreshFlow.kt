package pro.getline.vpn.getline.servers

import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineSubscriptionId

/**
 * The single data path for the Servers header "Refresh" action. Force-fetches
 * the managed profile's remote config and stops — `ProfileChanged` already
 * invalidates and reloads the Servers list, and re-probes latency when the
 * tab is visible, so a caller must not run a second reload/health-check on
 * top of this (GL-121: probe stays outside serversIoMutex).
 */
class ServersRefreshFlow(
    private val host: Host,
) {
    interface Host {
        fun managedProfileUuid(): String?

        suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult
    }

    enum class Outcome {
        Updated,

        /** No managed profile bound — nothing this action can refresh. */
        NoManagedProfile,
        Failed,
    }

    suspend fun refresh(): Outcome {
        val managed = host.managedProfileUuid() ?: return Outcome.NoManagedProfile
        val id = GetLineSubscriptionId(managed)
        return if (host.requestConfigUpdate(id) == ConfigUpdateResult.Unavailable) {
            Outcome.Failed
        } else {
            Outcome.Updated
        }
    }
}
