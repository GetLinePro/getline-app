package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException

/**
 * Pure product logout glue. Keeps Activity code thin and unit-testable.
 */
object ProductNavigationPolicy {
    /**
     * After session clear, managed-profile delete is best-effort.
     * Never rethrow — including [CancellationException] — so logout still
     * navigates to Onboarding instead of leaving a cleared Home stuck.
     */
    suspend fun bestEffortAfterLogout(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: CancellationException) {
            // Intentional: navigation must proceed after NonCancellable clear.
        } catch (_: Exception) {
            // Backend down / timeout — managed UUID may remain until next login.
        }
    }
}
