package pro.getline.vpn.getline

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.GetLineSessionRepository

/**
 * Owns confirmed product logout after the Activity has stopped its local start UI.
 *
 * Sign-out and remove-subscription deliberately clear session state at different
 * points. The Activity still owns confirmation, presentation, retry painting and
 * navigation; this flow returns only the teardown result those decisions need.
 */
internal class LogoutFlow(
    private val backend: GetLineBackend,
    private val sessionRepository: GetLineSessionRepository,
    private val host: Host,
) {
    interface Host {
        /** Cancel/clear Activity-owned account work after persisted session state changes. */
        suspend fun onSessionCleared()
    }

    enum class Action {
        SignOut,
        RemoveSubscription,
    }

    enum class Outcome {
        Completed,
        /** Removal failed before any account/session state was cleared. */
        RemoveSubscriptionFailed,
        /** Account tokens are gone, but the managed binding remains for retry. */
        SignOutFailed,
    }

    /**
     * Runs one confirmed teardown.
     *
     * Common prefix: stop VPN, then read cleanup state. RemoveSubscription keeps
     * the session until every owned profile is gone. SignOut drops tokens first so
     * a late account import cannot repopulate the binding, but keeps that binding
     * until deletion is proven.
     */
    suspend fun perform(action: Action): Outcome {
        // Always request stop: running may still be false while a start is in flight.
        backend.vpn.stop()

        val managedUuid = sessionRepository.managedProfileUuid()
        return when (action) {
            Action.RemoveSubscription -> removeSubscription(managedUuid)
            Action.SignOut -> signOut(managedUuid)
        }
    }

    private suspend fun removeSubscription(managedUuid: String?): Outcome {
        val oldProfilesDeleted = withContext(NonCancellable) {
            cleanupPendingProfiles(managedUuid)
        }
        if (!oldProfilesDeleted) return Outcome.RemoveSubscriptionFailed

        if (managedUuid != null) {
            val deleted = withContext(NonCancellable) {
                backend.subscriptions.deleteManaged(GetLineSubscriptionId(managedUuid))
            }
            if (deleted is GetLineBackendResult.Unavailable) {
                return Outcome.RemoveSubscriptionFailed
            }
        }

        withContext(NonCancellable) {
            sessionRepository.logout()
            host.onSessionCleared()
        }
        return Outcome.Completed
    }

    private suspend fun signOut(managedUuid: String?): Outcome {
        withContext(NonCancellable) {
            sessionRepository.discardSessionKeepingSubscription()
            host.onSessionCleared()
        }

        val oldProfilesDeleted = withContext(NonCancellable) {
            cleanupPendingProfiles(managedUuid)
        }
        if (!oldProfilesDeleted) return Outcome.SignOutFailed

        val deleted = if (managedUuid == null) {
            null
        } else {
            ProductNavigationPolicy.bestEffortAfterLogout {
                withContext(NonCancellable) {
                    backend.subscriptions.deleteManaged(GetLineSubscriptionId(managedUuid))
                }
            }
        }
        val clearBinding = ProductNavigationPolicy.clearBindingAfterSignOut(
            hadManagedProfile = managedUuid != null,
            deleteSucceeded = deleted is GetLineBackendResult.Success,
        )
        if (!clearBinding) return Outcome.SignOutFailed

        withContext(NonCancellable) {
            sessionRepository.logout()
        }
        return Outcome.Completed
    }

    /** VPN is already stopped; confirmed logout owns removal of every old binding. */
    private suspend fun cleanupPendingProfiles(managedUuid: String?): Boolean {
        var completed = true
        sessionRepository.pendingProfileCleanupUuids().forEach { pending ->
            val result = runPendingManagedProfileCleanup(
                pendingUuid = pending,
                managedUuid = managedUuid,
                canDelete = true,
                stopBeforeDelete = false,
                stopVpn = backend.vpn::stop,
                deleteManaged = backend.subscriptions::deleteManaged,
                clearPending = sessionRepository::clearPendingProfileCleanup,
            )
            if (result == ManagedProfileCleanupResult.Unavailable) {
                completed = false
            }
        }
        return completed
    }
}
