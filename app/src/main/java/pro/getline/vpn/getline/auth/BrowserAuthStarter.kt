package pro.getline.vpn.getline.auth

/**
 * Resolves the URL to open in [BrowserAuthLauncher] for a browser auth method.
 *
 * Both providers launch a same-origin trampoline on the portal host, which calls
 * the provider start endpoint from inside the browser:
 *
 * - Telegram: `GET /api/auth/telegram-oidc/start` must run in the browser so its
 *   HttpOnly PKCE cookies land in the Auth Tab jar.
 * - Google: `GET /api/auth/google/start` works from any client, but the browser
 *   must visit the portal origin first so the edge can set the marker cookie that
 *   scopes the callback-host rewrite to app logins (web logins keep landing on
 *   the SPA).
 *
 * Neither start endpoint is called from the app process any more.
 *
 * [AuthMethod.Email] is rejected — email uses OTP, not Auth Tab.
 */
object BrowserAuthStarter {
    fun resolveAuthUrl(method: AuthMethod): String {
        require(method.requiresBrowser()) {
            "AuthMethod.$method does not use browser auth"
        }
        return when (method) {
            AuthMethod.Google -> BrowserAuthLauncher.GOOGLE_TRAMPOLINE_URL
            AuthMethod.Telegram -> BrowserAuthLauncher.TELEGRAM_TRAMPOLINE_URL
            AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
        }
    }
}
