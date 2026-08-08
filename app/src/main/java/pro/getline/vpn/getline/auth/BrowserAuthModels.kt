package pro.getline.vpn.getline.auth

data class BrowserAuthStartResponse(
    val authUrl: String,
)

/** HTTPS / web-token handoff (Telegram trampoline + email OTP converge on token). */
data class WebAuthCallback(
    val authToken: String,
    val expiresInSeconds: Long?,
)

/**
 * Unified browser-callback payload. Discriminated by parameters, not provider.
 *
 * - [NativeCode] — Google/Telegram PKCE: `{applicationId}:/oauth2redirect?code=…`
 * - [WebToken] — Telegram: HTTPS fragment or `{applicationId}:/oauth2redirect?auth_token=…`
 */
sealed interface AuthCallbackResult {
    data class NativeCode(val code: String) : AuthCallbackResult

    data class WebToken(
        val authToken: String,
        val expiresInSeconds: Long? = null,
    ) : AuthCallbackResult
}
