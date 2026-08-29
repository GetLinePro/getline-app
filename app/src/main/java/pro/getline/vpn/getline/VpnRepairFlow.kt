package pro.getline.vpn.getline

import com.github.kr328.clash.common.log.Log
import pro.getline.vpn.getline.auth.ManagedBindingSnapshot
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.localproxy.LocalLanProxyOwnerIntegration

/**
 * Owns the idempotent repair ladder for the GetLine-managed VPN profile.
 *
 * Product/UI state remains the Activity's responsibility; this flow returns only
 * the repair result needed by those presentation decisions.
 */
internal class VpnRepairFlow(
    private val backend: GetLineBackend,
    private val sessionRepository: GetLineSessionRepository,
    private val host: Host,
    private val localProxyOwner: LocalLanProxyOwnerIntegration = LocalLanProxyOwnerIntegration.None,
) {
    interface Host {
        fun hasValidatedInternetConnection(): Boolean
        fun defaultProfileName(): String
    }

    enum class RepairOutcome {
        Ready,
        NeedsSetup,
        /** Local binding present but local heal failed; Retry may remote. */
        FailedPrepare,
        /** Remote was required/attempted and failed (offline or API). */
        FailedRestore,
        BackendUnavailable,
    }

    /**
     * Idempotent repair ladder (Retry = this method, not "download again"):
     * 1) inspect + local setActive(managed) when imported
     * 2) remote re-provision only if managed profile proven absent or corrupt
     *    and a path exists
     * 3) NeedsSetup when there is nothing to repair
     *
     * @param allowNetwork cold start / Retry may network; quiet resume stays local.
     */
    suspend fun repairVpnConfiguration(allowNetwork: Boolean): RepairOutcome {
        val binding = sessionRepository.managedBindingSnapshot()
        val managedUuid = binding.managedProfileUuid
        val hasSession = binding.hasSession
        val hasManaged = binding.hasManagedBinding
        val savedSource = binding.managedProfileSource
        val online = host.hasValidatedInternetConnection()

        // One GL-19 line for every exit, including startVpn()'s repair path.
        // Enum/bool tokens only — no UUID, URL, or Exception text.
        // session=/managed= aligned with startup_route (same hasRefreshToken / managed uuid).
        // step= policy step when plan() ran; na before plan (local ready / backend down).
        fun finish(outcome: RepairOutcome, step: String = "na"): RepairOutcome {
            Log.i(
                "repair_outcome outcome=${outcome.name} step=$step " +
                    "online=${if (online) 1 else 0} " +
                    "allow_net=${if (allowNetwork) 1 else 0} " +
                    "session=${if (hasSession) 1 else 0} " +
                    "managed=${if (hasManaged) 1 else 0}",
            )
            return outcome
        }

        var local = when (
            val result = backend.subscriptions.repairLocalActive(managedUuid)
        ) {
            GetLineBackendResult.Unavailable -> return finish(RepairOutcome.BackendUnavailable)
            is GetLineBackendResult.Success -> result.value
        }

        // Cleanup is independent from import success, but it may run only after
        // the replacement is proven present. If the selected row is itself an old
        // tombstone, first switch to the current managed UUID. A quiet ActivityStart
        // never stops the VPN: absent replacement keeps the old working profile.
        val pendingCleanupUuids = sessionRepository.pendingProfileCleanupUuids()
        val activeUuid = (local as? LocalActiveRepair.Ready)?.activeUuid
        if (
            activeUuid != null &&
            activeUuid != managedUuid &&
            activeUuid in pendingCleanupUuids &&
            !managedUuid.isNullOrBlank()
        ) {
            local = when (
                val activated = backend.subscriptions.activateIfImported(
                    GetLineSubscriptionId(managedUuid),
                )
            ) {
                GetLineBackendResult.Unavailable ->
                    return finish(RepairOutcome.BackendUnavailable)
                is GetLineBackendResult.Success -> if (activated.value) {
                    LocalActiveRepair.Ready(managedUuid)
                } else {
                    LocalActiveRepair.ManagedAbsent(
                        managedUuid = managedUuid,
                        managedIsImported = false,
                    )
                }
            }
        }
        val replacementReady =
            local is LocalActiveRepair.Ready && local.activeUuid == managedUuid
        pendingCleanupUuids.forEach { pending ->
            runPendingManagedProfileCleanup(
                pendingUuid = pending,
                managedUuid = managedUuid,
                canDelete = replacementReady,
                // activateIfImported emitted PROFILE_CHANGED and the live
                // ConfigurationModule loaded managed before old delete is broadcast.
                stopBeforeDelete = false,
                stopVpn = backend.vpn::stop,
                deleteManaged = backend.subscriptions::deleteManaged,
                clearPending = sessionRepository::clearPendingProfileCleanup,
            )
        }

        if (local is LocalActiveRepair.Ready) {
            return finish(RepairOutcome.Ready)
        }

        // Corrupt looks like "not imported" to the local-activate step: setActive
        // cannot recreate a missing/partial directory. Remote re-provision can.
        val managedIsImported = (local as? LocalActiveRepair.ManagedAbsent)
            ?.managedIsImported == true
        val step = VpnConfigurationRepairPolicy.plan(
            activeImportedUuid = null,
            managedUuid = managedUuid,
            managedIsImported = managedIsImported,
            hasSession = hasSession,
            hasSavedUrlSource = savedSource != null,
            allowNetwork = allowNetwork,
            online = online,
        )
        val stepName = step.name

        return when (step) {
            VpnConfigurationRepairPolicy.Step.Done,
            VpnConfigurationRepairPolicy.Step.LocalActivate -> {
                // Local activate should have succeeded inside repairLocalActive.
                // One defensive retry if inventory said managed is imported.
                if (managedIsImported) {
                    when (val again = backend.subscriptions.repairLocalActive(managedUuid)) {
                        GetLineBackendResult.Unavailable ->
                            return finish(RepairOutcome.BackendUnavailable, stepName)
                        is GetLineBackendResult.Success ->
                            if (again.value is LocalActiveRepair.Ready) {
                                return finish(RepairOutcome.Ready, stepName)
                            }
                    }
                }
                finish(RepairOutcome.FailedPrepare, stepName)
            }
            VpnConfigurationRepairPolicy.Step.NeedsSetup ->
                finish(RepairOutcome.NeedsSetup, stepName)
            VpnConfigurationRepairPolicy.Step.FailedLocalOnly ->
                finish(RepairOutcome.FailedPrepare, stepName)
            VpnConfigurationRepairPolicy.Step.OfflineForRemote ->
                finish(RepairOutcome.FailedRestore, stepName)
            VpnConfigurationRepairPolicy.Step.RemoteReprovision ->
                finish(reProvisionManagedProfile(managedUuid, binding), stepName)
        }
    }

    /**
     * Remote fallback only after local managed profile is proven absent or corrupt.
     *
     * Provenance order (do not replace a custom/URL import with account preferred):
     * 1) managed source bound to this managed UUID
     * 2) else native session preferred subscription (account-managed installs)
     *
     * Always reuses [managedUuid] when present so Retry does not mint duplicates.
     */
    private suspend fun reProvisionManagedProfile(
        managedUuid: String?,
        binding: ManagedBindingSnapshot,
    ): RepairOutcome {
        val managedId = managedUuid?.let { GetLineSubscriptionId(it) }
        val boundSource = binding.managedProfileSource

        val draft: GetLineSubscriptionDraft
        val subscriptionIdToRemember: String?

        if (boundSource != null) {
            draft = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = host.defaultProfileName(),
                source = boundSource,
            )
            // Keep existing subscription id; do not rewrite from preferred catalog.
            subscriptionIdToRemember = null
        } else if (binding.hasSession) {
            val subscription = sessionRepository.loadPreferredSubscriptionOrNull()
                ?: return RepairOutcome.FailedRestore
            val source = subscription.subscriptionLink ?: return RepairOutcome.FailedRestore
            draft = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = subscription.displayName ?: host.defaultProfileName(),
                source = source,
            )
            subscriptionIdToRemember = subscription.id
        } else {
            return RepairOutcome.NeedsSetup
        }

        return when (
            val reimported = backend.subscriptions.reimportAndActivate(draft, managedId)
        ) {
            GetLineBackendResult.Unavailable -> RepairOutcome.FailedRestore
            is GetLineBackendResult.Success -> {
                sessionRepository.rememberManagedProfile(
                    uuid = reimported.value.value,
                    source = draft.source,
                )
                if (subscriptionIdToRemember != null) {
                    sessionRepository.rememberSubscription(subscriptionIdToRemember)
                }
                // Re-import can bind a different managed profile than the one
                // the stored settings were written for.
                localProxyOwner.reconcileOwner()
                RepairOutcome.Ready
            }
        }
    }
}
