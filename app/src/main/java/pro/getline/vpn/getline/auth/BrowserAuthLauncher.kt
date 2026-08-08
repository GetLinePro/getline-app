package pro.getline.vpn.getline.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsService
import com.github.kr328.clash.common.log.Log
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import pro.getline.vpn.getlineui.GetLineScreen
import pro.getline.vpn.product.GetLineActivity

/**
 * How Auth Tab should recognize completion.
 *
 * [NativeScheme] — Google/Telegram PKCE (`EXTRA_REDIRECT_SCHEME`).
 * [HttpsCallback] — legacy HTTPS trampoline completion for rollback only
 * (`EXTRA_HTTPS_REDIRECT_*`).
 */
enum class AuthTabRedirectMode {
    NativeScheme,
    HttpsCallback,
}

/**
 * Opens a server-provided auth URL using the best available browser capability:
 * Auth Tab → Custom Tabs → external browser ([Intent.ACTION_VIEW]). The current
 * Google and Telegram flows use [AuthTabRedirectMode.NativeScheme];
 * [AuthTabRedirectMode.HttpsCallback] remains for the legacy rollback path.
 *
 * Auth Tab returns the callback via [ActivityResult]; Custom Tab and external
 * browser complete only through the exported deep-link Activity (or edge page).
 */
class BrowserAuthLauncher {
    suspend fun <D : GetLineScreen<*>> launch(
        activity: GetLineActivity<D>,
        authUrl: String,
        redirectMode: AuthTabRedirectMode = AuthTabRedirectMode.NativeScheme,
    ): BrowserAuthLaunchResult {
        val launchUri = parseAndValidateLaunchUrl(authUrl)

        return when (val capability = resolveBrowserCapability(activity)) {
            is BrowserCapability.AuthTab ->
                launchAuthTab(activity, launchUri, capability.packageName, redirectMode)
            is BrowserCapability.CustomTab ->
                launchCustomTab(activity, launchUri, capability.packageName)
            BrowserCapability.ExternalBrowser -> launchExternalBrowser(activity, launchUri)
            BrowserCapability.None -> BrowserAuthLaunchResult.NoBrowser
        }
    }

    private suspend fun <D : GetLineScreen<*>> launchAuthTab(
        activity: GetLineActivity<D>,
        launchUri: Uri,
        browserPackage: String,
        redirectMode: AuthTabRedirectMode,
    ): BrowserAuthLaunchResult {
        val authTab = AuthTabIntent.Builder().build()
        val intent = Intent(authTab.intent).apply {
            data = launchUri
            setPackage(browserPackage)
            when (redirectMode) {
                AuthTabRedirectMode.NativeScheme -> {
                    putExtra(
                        AuthTabIntent.EXTRA_REDIRECT_SCHEME,
                        AppEnvironment.nativeCallbackScheme,
                    )
                }
                AuthTabRedirectMode.HttpsCallback -> {
                    putExtra(
                        AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST,
                        AppEnvironment.callbackHost,
                    )
                    putExtra(
                        AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH,
                        AuthCallbackParser.HTTPS_REDIRECT_PATH,
                    )
                }
            }
        }

        val startedAt = SystemClock.elapsedRealtime()
        val result = activity.startActivityForResult(
            AuthTabIntent.AuthenticateUserResultContract(),
            intent,
        )

        // Numbers and package name only — never resultUri or authUrl.
        Log.w(
            "auth_tab_result code=${result.resultCode} " +
                "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} " +
                "browser=$browserPackage mode=${redirectMode.name}",
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

    private fun launchCustomTab(
        activity: Context,
        launchUri: Uri,
        browserPackage: String,
    ): BrowserAuthLaunchResult {
        return try {
            val customTabs = CustomTabsIntent.Builder().build()
            customTabs.intent.setPackage(browserPackage)
            customTabs.launchUrl(activity, launchUri)
            Log.i("browser_auth_custom_tab package=$browserPackage")
            BrowserAuthLaunchResult.AwaitingDeepLink
        } catch (_: ActivityNotFoundException) {
            BrowserAuthLaunchResult.NoBrowser
        }
    }

    private fun launchExternalBrowser(
        activity: Context,
        launchUri: Uri,
    ): BrowserAuthLaunchResult {
        return try {
            // Pin package from hostless https:// resolve (generic browsers only).
            // A bare ACTION_VIEW of app.getline.pro can open a verified WebAPK
            // (scope "/"); setPackage avoids that when a browser is installed.
            // No pin when the resolve is the system chooser — let it be shown.
            val intent = Intent(Intent.ACTION_VIEW, launchUri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                resolveGenericHttpsBrowserPackage(activity)?.let { setPackage(it) }
            }
            activity.startActivity(intent)
            Log.i("browser_auth_external_view")
            BrowserAuthLaunchResult.AwaitingDeepLink
        } catch (_: ActivityNotFoundException) {
            BrowserAuthLaunchResult.NoBrowser
        }
    }

    /**
     * Capability ladder: Auth Tab → any Custom Tabs provider → generic HTTPS browser → none.
     * Chrome is not prioritized outside the existing Auth Tab preferred list.
     *
     * External rung uses a hostless `https://` probe so WebAPK / verified app-link
     * handlers for a product host do not count as "has a browser".
     */
    fun resolveBrowserCapability(context: Context): BrowserCapability {
        resolveAuthTabPackage(context)?.let { return BrowserCapability.AuthTab(it) }

        val customTabPackage = CustomTabsClient.getPackageName(context, null, false)
            ?: installedCustomTabsPackages(context).firstOrNull()
        if (customTabPackage != null) {
            return BrowserCapability.CustomTab(customTabPackage)
        }

        if (hasGenericHttpsBrowser(context)) {
            return BrowserCapability.ExternalBrowser
        }
        return BrowserCapability.None
    }

    /**
     * Package to pin for the external rung, or null when there is nothing safe to
     * pin. Several browsers with no default resolve to the system chooser
     * (`packageName == "android"`); pinning that makes [Intent.setPackage] throw.
     * Null therefore means "launch unpinned", not "no browser" — presence is
     * [hasGenericHttpsBrowser].
     */
    fun resolveGenericHttpsBrowserPackage(context: Context): String? =
        resolveGenericHttpsBrowser(context)
            ?.activityInfo
            ?.packageName
            ?.takeIf { it != "android" }

    /** True when any generic browser handles hostless `https://` (chooser counts). */
    private fun hasGenericHttpsBrowser(context: Context): Boolean =
        resolveGenericHttpsBrowser(context) != null

    /**
     * Resolve [Intent.ACTION_VIEW] + [Intent.CATEGORY_BROWSABLE] on a hostless
     * `https://` URI. Generic browser filters match; host-bound WebAPK /
     * app-link filters do not.
     */
    private fun resolveGenericHttpsBrowser(context: Context): ResolveInfo? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return context.packageManager.resolveActivity(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
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
            GetLineControlPlaneHostPolicy.requireBrowserLaunchUrl(trimmed)
            return uri
        }
    }
}

sealed interface BrowserCapability {
    data class AuthTab(val packageName: String) : BrowserCapability
    data class CustomTab(val packageName: String) : BrowserCapability
    data object ExternalBrowser : BrowserCapability
    data object None : BrowserCapability
}

sealed interface BrowserAuthLaunchResult {
    /** Auth Tab returned a callback URI via ActivityResult. */
    data class Completed(val callbackUri: Uri) : BrowserAuthLaunchResult

    /** Custom Tab / external browser launched; completion arrives via deep link. */
    data object AwaitingDeepLink : BrowserAuthLaunchResult

    data object Cancelled : BrowserAuthLaunchResult
    data object VerificationFailed : BrowserAuthLaunchResult
    data object NoBrowser : BrowserAuthLaunchResult
    data class Invalid(val message: String) : BrowserAuthLaunchResult
}
