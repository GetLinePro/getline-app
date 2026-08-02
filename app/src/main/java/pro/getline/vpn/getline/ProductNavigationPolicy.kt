package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException

/**
 * Pure product logout glue. Keeps Activity code thin and unit-testable.
 */
object ProductNavigationPolicy {
    /**
     * Managed-profile delete during logout is best-effort.
     * Never rethrow — including [CancellationException] — so logout still
     * finishes instead of leaving a cleared Home stuck.
     *
     * Returns `null` when the block failed, so the caller can tell "profile is
     * gone" from "we could not find out" ([clearBindingAfterSignOut]).
     */
    suspend fun <T> bestEffortAfterLogout(block: suspend () -> T): T? {
        return try {
            block()
        } catch (_: CancellationException) {
            // Intentional: navigation must proceed after NonCancellable clear.
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Sign-out invariant: the binding may only be dropped once its profile is
     * really gone.
     *
     * The binding ([GetLineSessionStore.managedProfileUuid] and friends) is the
     * only thing pointing at an imported profile. Clearing it after a failed
     * delete leaves a profile that Home still routes to, with no product UI to
     * refresh or remove it — reachable only through Advanced.
     */
    fun clearBindingAfterSignOut(hadManagedProfile: Boolean, deleteSucceeded: Boolean): Boolean {
        return !hadManagedProfile || deleteSucceeded
    }
}
