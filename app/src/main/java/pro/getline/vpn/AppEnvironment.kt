package pro.getline.vpn

import com.github.kr328.clash.BuildConfig

/**
 * Flavor-backed GetLine service endpoints (app module only).
 *
 * Values come from the `environment` product flavor (`prod` / `e2e`).
 * Do not hardcode `app.getline.pro` in auth/portal call sites — read here.
 *
 * Host isolation for control-plane URLs is [GetLineControlPlaneHostPolicy].
 */
object AppEnvironment {
    val apiOrigin: String
        get() = BuildConfig.GETLINE_API_ORIGIN

    val authOrigin: String
        get() = BuildConfig.GETLINE_AUTH_ORIGIN

    val callbackHost: String
        get() = BuildConfig.GETLINE_CALLBACK_HOST

    val portalOrigin: String
        get() = BuildConfig.GETLINE_PORTAL_ORIGIN

    /**
     * Private-use reverse-DNS scheme for native OAuth callback
     * (`pro.getline.vpn`, `pro.getline.vpn.alpha`, …). No trailing colon.
     */
    val nativeCallbackScheme: String
        get() = BuildConfig.APPLICATION_ID

    /**
     * Native PKCE redirect URI. Single slash after the scheme
     * (`pro.getline.vpn.alpha:/oauth2redirect`) — double-slash is a different URI
     * and is rejected by production whitelist.
     */
    val nativeCallbackUri: String
        get() = "${BuildConfig.APPLICATION_ID}:/oauth2redirect"

    /**
     * Legacy Telegram-only portal trampoline (sets edge marker cookie, runs OIDC
     * start in the browser jar). The current client uses native PKCE and does
     * not open this URL; retain it for code-only rollback builds.
     * Must not be `/` — that is the HTTPS completion path.
     *
     * `app_id` is read by trampoline HTML to set `gl_app_id` for the auth
     * callback page (step 25). Whitelist lives in that HTML.
     */
    val telegramTrampolineUrl: String
        get() {
            val base = portalOrigin.trimEnd('/') + "/android-auth/telegram"
            val appId = java.net.URLEncoder.encode(
                BuildConfig.APPLICATION_ID,
                java.nio.charset.StandardCharsets.UTF_8.name(),
            )
            return "$base?app_id=$appId"
        }
}
