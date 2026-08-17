package pro.getline.vpn.getline.auth

import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionSummary

/**
 * The single data path for the Subscription card. Account APIs are deliberately
 * absent: a native session can provide a refresh hint, never card content.
 */
class SubscriptionCardFlow(
    private val host: Host,
) {
    interface Host {
        fun managedProfileUuid(): String?

        suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult

        suspend fun findImported(
            id: GetLineSubscriptionId,
        ): GetLineBackendResult<GetLineSubscriptionSummary?>
    }

    data class Outcome(
        val hasManagedBinding: Boolean,
        val summary: GetLineSubscriptionSummary?,
        val failed: Boolean,
    )

    /** Forced triggers refresh the remote profile before reading its saved row. */
    suspend fun materialize(refreshManaged: Boolean): Outcome {
        val managed = host.managedProfileUuid()
            ?: return Outcome(
                hasManagedBinding = false,
                summary = null,
                failed = false,
            )
        val id = GetLineSubscriptionId(managed)
        val updateFailed = refreshManaged &&
            host.requestConfigUpdate(id) == ConfigUpdateResult.Unavailable
        return when (val local = host.findImported(id)) {
            is GetLineBackendResult.Success -> Outcome(
                hasManagedBinding = true,
                summary = local.value,
                failed = updateFailed,
            )
            GetLineBackendResult.Unavailable -> Outcome(
                hasManagedBinding = true,
                summary = null,
                failed = true,
            )
        }
    }
}
