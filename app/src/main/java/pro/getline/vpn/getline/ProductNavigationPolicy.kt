package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException

/**
 * Pure product navigation glue. Keeps Activity code thin and unit-testable.
 */
object ProductNavigationPolicy {
    /**
     * Necessary condition for Home to keep the product shell: a native session
     * or a managed binding.
     *
     * Not the full routing table. Startup still asks the backend hasImported
     * probe so a dead :background stays distinguishable from a clean install,
     * and a session with proven-empty inventory still goes to Onboarding.
     * Home uses this as a hard guard: if both handles disappear at runtime,
     * leave for Onboarding.
     */
    fun canOwnProductShell(hasSession: Boolean, hasManagedBinding: Boolean): Boolean =
        hasSession || hasManagedBinding

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
