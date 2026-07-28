package pro.getline.vpn.getline.accountportal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import pro.getline.vpn.design.R as DesignR

/**
 * Opens the GetLine account portal in a Custom Tab (or browser fallback).
 *
 * Not an OAuth Auth Tab flow — plain browsing with the browser cookie jar.
 * Does not attach Authorization headers, tokens in URL, or WebView bridges.
 */
interface AccountPortalLauncher {
    fun open(context: Context, uri: Uri): AccountPortalLaunchResult
}

/**
 * @param startActivity injectable for unit tests; default uses [Context.startActivity].
 * @param customTabsBuilder factory for [CustomTabsIntent]; tests can inject a stub.
 */
class DefaultAccountPortalLauncher(
    private val uriPolicy: AccountPortalUriPolicy = AccountPortalUriPolicy,
    private val startActivity: (Context, Intent) -> Unit = { context, intent ->
        context.startActivity(intent)
    },
    private val buildCustomTabsIntent: (Context) -> CustomTabsIntent = { context ->
        buildBrandedCustomTabsIntent(context)
    },
) : AccountPortalLauncher {

    @Volatile
    private var launchInFlight = false

    override fun open(context: Context, uri: Uri): AccountPortalLaunchResult {
        if (!uriPolicy.isAllowedPortalUri(uri) || uriPolicy.containsSecrets(uri)) {
            return AccountPortalLaunchResult.RejectedUri
        }

        synchronized(this) {
            if (launchInFlight) {
                return AccountPortalLaunchResult.AlreadyInProgress
            }
            launchInFlight = true
        }

        return try {
            openValidated(context, uri)
        } finally {
            // In-flight gate is only for the synchronous open call / double-tap.
            // Multi-tab prevention after success is owned by VisitCoordinator.
            synchronized(this) {
                launchInFlight = false
            }
        }
    }

    private fun openValidated(context: Context, uri: Uri): AccountPortalLaunchResult {
        return try {
            val customTabs = buildCustomTabsIntent(context)
            val intent = Intent(customTabs.intent).apply {
                data = uri
                // Do not set package — use the user's default browser / Custom Tabs provider.
                // Do not put tokens or Authorization extras.
            }
            startActivity(context, intent)
            AccountPortalLaunchResult.Launched
        } catch (_: ActivityNotFoundException) {
            openWithActionView(context, uri)
        } catch (t: Throwable) {
            // Custom Tab path failed for another reason — try plain VIEW before giving up.
            val fallback = openWithActionView(context, uri)
            if (fallback is AccountPortalLaunchResult.Launched ||
                fallback is AccountPortalLaunchResult.NoBrowserAvailable
            ) {
                fallback
            } else {
                AccountPortalLaunchResult.Failed(t)
            }
        }
    }

    private fun openWithActionView(context: Context, uri: Uri): AccountPortalLaunchResult {
        // Re-check policy on fallback path (same validated URI only).
        if (!uriPolicy.isAllowedPortalUri(uri) || uriPolicy.containsSecrets(uri)) {
            return AccountPortalLaunchResult.RejectedUri
        }
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
            startActivity(context, viewIntent)
            AccountPortalLaunchResult.Launched
        } catch (_: ActivityNotFoundException) {
            AccountPortalLaunchResult.NoBrowserAvailable
        } catch (t: Throwable) {
            AccountPortalLaunchResult.Failed(t)
        }
    }

    companion object {
        fun buildBrandedCustomTabsIntent(context: Context): CustomTabsIntent {
            val toolbarColor = ContextCompat.getColor(context, DesignR.color.getline_brand_background)
            val navBarColor = ContextCompat.getColor(context, DesignR.color.getline_brand_surface)
            val darkParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .setNavigationBarColor(navBarColor)
                .build()

            return CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                .setColorScheme(CustomTabsIntent.COLOR_SCHEME_DARK)
                .setDefaultColorSchemeParams(darkParams)
                .build()
        }
    }
}
