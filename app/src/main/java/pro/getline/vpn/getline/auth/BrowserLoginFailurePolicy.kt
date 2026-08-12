package pro.getline.vpn.getline.auth

/**
 * Catch disposition after browser PKCE login throws.
 *
 * [completeLoginFromNativeCode] sets `postLoginImportConsumed` before the
 * subscription step. Empty accounts throw [GetLineAuthException.NoSubscription]
 * with a live session; routing that to `runPostNativeLoginImport` no-ops and
 * leaves onboarding on Loading (no trial CTA).
 */
internal enum class BrowserLoginFailureDisposition {
    /** Session + post-session step already ran → applyLoginFailure(ImportPreferred). */
    PostSessionFailure,

    /** Session from sibling package VIEW; post-session step not consumed yet. */
    SiblingSessionImport,

    /** No session → applyLoginFailure(BrowserLogin). */
    PreSessionFailure,
}

internal object BrowserLoginFailurePolicy {
    fun disposition(
        hasSession: Boolean,
        postLoginImportConsumed: Boolean,
    ): BrowserLoginFailureDisposition = when {
        hasSession && postLoginImportConsumed ->
            BrowserLoginFailureDisposition.PostSessionFailure
        hasSession -> BrowserLoginFailureDisposition.SiblingSessionImport
        else -> BrowserLoginFailureDisposition.PreSessionFailure
    }
}
