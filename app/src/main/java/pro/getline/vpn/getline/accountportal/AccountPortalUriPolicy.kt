package pro.getline.vpn.getline.accountportal

import android.net.Uri
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi

/**
 * Builds and validates the GetLine web account portal URI.
 *
 * Origin is taken from the existing auth API base ([RwpGetLineAuthApi.DEFAULT_ORIGIN]).
 * Tokens must never appear in query, fragment, or path secrets.
 */
object AccountPortalUriPolicy {
    const val EXPECTED_SCHEME = "https"
    const val EXPECTED_HOST = "app.getline.pro"
    const val DASHBOARD_FRAGMENT = "/my-dashboard"

    /**
     * Dashboard entry: `{origin}/#/my-dashboard` with no query or tokens.
     */
    fun dashboardUri(): Uri {
        val origin = RwpGetLineAuthApi.DEFAULT_ORIGIN.trimEnd('/')
        val uri = Uri.parse("$origin/#$DASHBOARD_FRAGMENT")
        require(isAllowedPortalUri(uri)) {
            "Constructed portal URI failed policy validation"
        }
        require(!containsSecrets(uri)) {
            "Constructed portal URI must not contain token material"
        }
        return uri
    }

    /**
     * Allows only HTTPS URIs on the exact host [EXPECTED_HOST].
     * Rejects cleartext, wrong hosts, subdomain spoofing, and userinfo.
     * Does not use [String.startsWith] for host checks.
     */
    fun isAllowedPortalUri(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        if (!EXPECTED_SCHEME.equals(scheme, ignoreCase = true)) {
            return false
        }

        // userinfo@host spoofing (e.g. https://app.getline.pro@evil.example/)
        if (!uri.userInfo.isNullOrEmpty()) {
            return false
        }

        val host = uri.host ?: return false
        if (!EXPECTED_HOST.equals(host, ignoreCase = true)) {
            return false
        }

        // Port must be default HTTPS or absent.
        val port = uri.port
        if (port != -1 && port != 443) {
            return false
        }

        return true
    }

    /**
     * Best-effort secret detection for constructed or accidental URIs.
     * Does not log URI contents.
     */
    fun containsSecrets(uri: Uri): Boolean {
        val query = uri.encodedQuery.orEmpty()
        val fragment = uri.encodedFragment.orEmpty()
        val combined = buildString {
            append(query)
            append('&')
            append(fragment)
        }.lowercase()

        return SECRET_QUERY_MARKERS.any { marker ->
            combined.contains(marker)
        }
    }

    private val SECRET_QUERY_MARKERS = listOf(
        "auth_token=",
        "access_token=",
        "refresh_token=",
        "token=",
        "bearer=",
        "authorization=",
    )
}
