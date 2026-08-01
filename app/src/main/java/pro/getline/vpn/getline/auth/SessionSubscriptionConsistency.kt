package pro.getline.vpn.getline.auth

/**
 * Classifies local session tokens vs managed profile binding.
 * Used for diagnostics (bug 3) and e2e guards — not for UI copy.
 */
object SessionSubscriptionConsistency {
    enum class Verdict {
        /** No session and no binding. */
        Empty,

        /** Native session present; managed profile may or may not exist yet. */
        SessionOk,

        /**
         * Managed binding without refresh token.
         * Steady-state for link-only (URL import without account session).
         * Still anomalous only when session is expected (e.g. right after login)
         * and binding is missing — that case is the inverse of this verdict.
         */
        BindingWithoutSession,
    }

    fun classify(hasSession: Boolean, hasManagedBinding: Boolean): Verdict {
        return when {
            hasSession -> Verdict.SessionOk
            hasManagedBinding -> Verdict.BindingWithoutSession
            else -> Verdict.Empty
        }
    }
}
