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

    /** Same-origin Telegram trampoline on the portal host. */
    val telegramTrampolineUrl: String
        get() = portalOrigin.trimEnd('/') + "/android-auth/telegram"

    /** `return_to` for Telegram OIDC start (portal root). */
    val telegramReturnTo: String
        get() = portalOrigin.trimEnd('/') + "/"
}
