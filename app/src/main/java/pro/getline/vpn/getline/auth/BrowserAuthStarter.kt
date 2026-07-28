package pro.getline.vpn.getline.auth

/**
 * Resolves the URL to open in [BrowserAuthLauncher] for a browser auth method.
 *
 * Google: app calls `GET /api/auth/google/start`, then launches returned `auth_url`.
 * Telegram: launches the same-origin trampoline so the browser itself calls
 * `GET /api/auth/telegram-oidc/start` and stores PKCE cookies in the Auth Tab jar.
 * Calling Telegram start from the app process would set cookies outside the browser
 * and break the OAuth callback.
 *
 * [AuthMethod.Email] is rejected — email uses OTP, not Auth Tab.
 */
class BrowserAuthStarter(
    private val api: GetLineAuthApi,
) {
    suspend fun resolveAuthUrl(method: AuthMethod): String {
        require(method.requiresBrowser()) {
            "AuthMethod.$method does not use browser auth"
        }
        return when (method) {
            AuthMethod.Google -> {
                val response = api.startBrowserAuth(AuthMethod.Google)
                val authUrl = response.authUrl
                BrowserAuthLauncher.parseAndValidateLaunchUrl(authUrl)
                authUrl
            }
            AuthMethod.Telegram -> {
                BrowserAuthLauncher.TELEGRAM_TRAMPOLINE_URL
            }
            AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
        }
    }
}
