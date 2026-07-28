package pro.getline.vpn.getline.auth

import android.net.Uri

/**
 * Parses the shared HTTPS Auth Tab completion URI used by all browser providers:
 *
 * `https://app.getline.pro/#/login?auth_token=...&expires_in=86400`
 * `https://app.getline.pro/#/login?auth_error=...`
 *
 * Token values are never included in exception messages.
 */
object AuthCallbackParser {
    private const val EXPECTED_SCHEME = "https"
    private const val EXPECTED_HOST = "app.getline.pro"
    private const val EXPECTED_PATH = "/"
    private const val FRAGMENT_PREFIX = "/login?"

    fun parse(uri: Uri?): WebAuthCallback {
        val params = parseParams(uri)

        val authError = params.getQueryParameter("auth_error")
            ?.takeIf { it.isNotBlank() }
        if (authError != null) {
            // Do not embed error payload details that might include sensitive material.
            throw GetLineAuthException.InvalidCallback("Authentication error from provider")
        }

        val token = params.getQueryParameter("auth_token")
            ?.takeIf { it.isNotBlank() }
            ?: throw GetLineAuthException.InvalidCallback("Missing auth result")

        val expiresIn = params.getQueryParameter("expires_in")
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()

        return WebAuthCallback(
            authToken = token,
            expiresInSeconds = expiresIn,
        )
    }

    /**
     * Convenience for callers that only need the web bearer token.
     */
    fun parseWebAuthToken(uri: Uri?): String = parse(uri).authToken

    internal fun parseParams(uri: Uri?): Uri {
        if (uri == null) {
            throw GetLineAuthException.InvalidCallback("Missing callback URI")
        }

        if (!EXPECTED_SCHEME.equals(uri.scheme, ignoreCase = true)) {
            throw GetLineAuthException.InvalidCallback("Unexpected scheme")
        }
        if (!EXPECTED_HOST.equals(uri.host, ignoreCase = true)) {
            throw GetLineAuthException.InvalidCallback("Unexpected host")
        }

        val path = uri.path.orEmpty().ifEmpty { "/" }
        if (path != EXPECTED_PATH) {
            throw GetLineAuthException.InvalidCallback("Unexpected path")
        }

        val fragment = uri.fragment
            ?: throw GetLineAuthException.InvalidCallback("Missing fragment")

        if (!fragment.startsWith(FRAGMENT_PREFIX)) {
            throw GetLineAuthException.InvalidCallback("Unexpected fragment")
        }

        val query = fragment.removePrefix("/login?")
        if (query.isBlank()) {
            throw GetLineAuthException.InvalidCallback("Empty auth fragment")
        }

        return Uri.parse("https://local/?$query")
    }
}
