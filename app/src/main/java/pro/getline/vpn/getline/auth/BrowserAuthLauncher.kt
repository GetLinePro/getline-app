package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsService
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.getlineui.GetLineScreen
import pro.getline.vpn.product.GetLineActivity

/**
 * Opens a server-provided auth URL in an AndroidX Auth Tab and returns the
 * HTTPS completion URI (or cancellation / verification failure).
 *
 * The launcher is provider-agnostic: callers obtain `auth_url` (or a same-origin
 * trampoline that resolves it in-browser) and pass it here unchanged.
 *
 * Completion host/path come from [AppEnvironment] (prod or e2e flavor).
 *
 * Start URL must not be the HTTPS completion path (`/`), otherwise supporting
 * browsers can treat the initial navigation as completion.
 */
class BrowserAuthLauncher {
    suspend fun <D : GetLineScreen<*>> launch(
        activity: GetLineActivity<D>,
        authUrl: String,
    ): BrowserAuthLaunchResult {
        val launchUri = parseAndValidateLaunchUrl(authUrl)

        val browserPackage = resolveAuthTabPackage(activity)
            ?: throw GetLineAuthException.Protocol("No Auth Tab-capable browser")

        val authTab = AuthTabIntent.Builder().build()
        val intent = Intent(authTab.intent).apply {
            data = launchUri
            setPackage(browserPackage)
            putExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST, AppEnvironment.callbackHost)
            putExtra(AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH, REDIRECT_PATH)
        }

        val result = activity.startActivityForResult(
            AuthTabIntent.AuthenticateUserResultContract(),
            intent,
        )

        return when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> {
                val uri = result.resultUri
                    ?: return BrowserAuthLaunchResult.Invalid("Empty result URI")
                BrowserAuthLaunchResult.Completed(uri)
            }
            AuthTabIntent.RESULT_CANCELED -> BrowserAuthLaunchResult.Cancelled
            AuthTabIntent.RESULT_VERIFICATION_FAILED,
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT ->
                BrowserAuthLaunchResult.VerificationFailed
            else -> BrowserAuthLaunchResult.Invalid("Unexpected auth result")
        }
    }

    /**
     * Prefers the default Custom Tabs provider when it supports Auth Tab, then
     * known Chromium packages, then any installed Custom Tabs service that
     * advertises the Auth Tab category.
     */
    fun resolveAuthTabPackage(context: Context): String? {
        val preferred = CustomTabsClient.getPackageName(context, PREFERRED_BROWSERS, true)
        if (preferred != null && CustomTabsClient.isAuthTabSupported(context, preferred)) {
            return preferred
        }

        for (candidate in PREFERRED_BROWSERS) {
            if (CustomTabsClient.isAuthTabSupported(context, candidate)) {
                return candidate
            }
        }

        return installedCustomTabsPackages(context).firstOrNull { packageName ->
            CustomTabsClient.isAuthTabSupported(context, packageName)
        }
    }

    private fun installedCustomTabsPackages(context: Context): List<String> {
        val pm = context.packageManager
        val serviceIntent = Intent(CustomTabsService.ACTION_CUSTOM_TABS_CONNECTION)
        val resolveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            0
        }
        return pm.queryIntentServices(serviceIntent, resolveFlags)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }

    companion object {
        /**
         * Same-origin trampoline for Telegram. The trampoline HTML calls
         * `/api/auth/telegram-oidc/start` inside the browser so PKCE cookies
         * land in the Auth Tab jar. See `docs/spikes/android-auth/`.
         */
        val TELEGRAM_TRAMPOLINE_URL: String
            get() = AppEnvironment.telegramTrampolineUrl

        const val REDIRECT_PATH = "/"

        private val PREFERRED_BROWSERS = listOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.google.android.apps.chrome",
            "com.microsoft.emmx",
            "com.brave.browser",
        )

        fun parseAndValidateLaunchUrl(authUrl: String): Uri {
            val trimmed = authUrl.trim()
            if (trimmed.isEmpty()) {
                throw GetLineAuthException.Protocol("auth_url is blank")
            }
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") {
                throw GetLineAuthException.Protocol("auth_url must be https")
            }
            if (uri.host.isNullOrBlank()) {
                throw GetLineAuthException.Protocol("auth_url host missing")
            }
            // E2E: only stage product hosts. Prod: block wrong-env GetLine hosts;
            // third-party OAuth (accounts.google.com) still allowed on prod.
            GetLineControlPlaneHostPolicy.requireBrowserLaunchUrl(trimmed)
            return uri
        }
    }
}

sealed interface BrowserAuthLaunchResult {
    data class Completed(val callbackUri: Uri) : BrowserAuthLaunchResult
    data object Cancelled : BrowserAuthLaunchResult
    data object VerificationFailed : BrowserAuthLaunchResult
    data class Invalid(val message: String) : BrowserAuthLaunchResult
}
