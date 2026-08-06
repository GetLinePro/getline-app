package pro.getline.vpn.getline.auth

import android.net.Uri
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy

/**
 * Dual browser-callback parser (issue #19 step 23).
 *
 * 1. Native package URI `{applicationId}:/oauth2redirect`:
 *    - `?code=` → [AuthCallbackResult.NativeCode] (Google PKCE)
 *    - `?auth_token=` → [AuthCallbackResult.WebToken] (Telegram deep link / edge page)
 * 2. HTTPS Auth Tab completion `https://{callbackHost}/#/login?auth_token=…`
 *    → [AuthCallbackResult.WebToken] (Telegram with Auth Tab)
 *
 * Token/code values never appear in exception messages.
 */
object AuthCallbackParser {
    const val NATIVE_PATH = "/oauth2redirect"
    const val HTTPS_REDIRECT_PATH = "/"
    private const val FRAGMENT_PREFIX = "/login?"

    fun parse(uri: Uri?): AuthCallbackResult {
        if (uri == null) {
            throw GetLineAuthException.InvalidCallback("Missing callback URI")
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw GetLineAuthException.InvalidCallback("Unexpected scheme")

        return if (scheme == "https") {
            parseHttpsWebToken(uri)
        } else {
            parseNativePackageUri(uri)
        }
    }

    private fun parseNativePackageUri(uri: Uri): AuthCallbackResult {
        val expectedScheme = AppEnvironment.nativeCallbackScheme
        if (!expectedScheme.equals(uri.scheme, ignoreCase = true)) {
            throw GetLineAuthException.InvalidCallback("Unexpected scheme")
        }
        // Private-use URI must be scheme:/path — not scheme://host/path.
        if (!uri.host.isNullOrEmpty()) {
            throw GetLineAuthException.InvalidCallback("Unexpected authority")
        }
        val path = uri.path.orEmpty().ifEmpty { "/" }
        if (path != NATIVE_PATH) {
            throw GetLineAuthException.InvalidCallback("Unexpected path")
        }

        val error = uri.getQueryParameter("error")?.takeIf { it.isNotBlank() }
            ?: uri.getQueryParameter("auth_error")?.takeIf { it.isNotBlank() }
        if (error != null) {
            throw GetLineAuthException.InvalidCallback("Authentication error from provider")
        }

        val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        if (code != null) {
            return AuthCallbackResult.NativeCode(code)
        }

        val token = uri.getQueryParameter("auth_token")?.takeIf { it.isNotBlank() }
            ?: throw GetLineAuthException.InvalidCallback("Missing auth result")
        val expiresIn = uri.getQueryParameter("expires_in")
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        return AuthCallbackResult.WebToken(authToken = token, expiresInSeconds = expiresIn)
    }

    private fun parseHttpsWebToken(uri: Uri): AuthCallbackResult {
        val expected = GetLineControlPlaneHostPolicy.canonicalizeHost(
            AppEnvironment.callbackHost,
        )
        val actual = GetLineControlPlaneHostPolicy.canonicalizeHost(uri.host)
        if (expected == null || actual == null || expected != actual) {
            throw GetLineAuthException.InvalidCallback("Unexpected host")
        }
        if (!GetLineControlPlaneHostPolicy.isAllowedProductHost(actual)) {
            throw GetLineAuthException.InvalidCallback("Unexpected host")
        }

        val path = uri.path.orEmpty().ifEmpty { "/" }
        if (path != HTTPS_REDIRECT_PATH) {
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
        val params = Uri.parse("https://local/?$query")

        val authError = params.getQueryParameter("auth_error")?.takeIf { it.isNotBlank() }
        if (authError != null) {
            throw GetLineAuthException.InvalidCallback("Authentication error from provider")
        }
        val token = params.getQueryParameter("auth_token")?.takeIf { it.isNotBlank() }
            ?: throw GetLineAuthException.InvalidCallback("Missing auth result")
        val expiresIn = params.getQueryParameter("expires_in")
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
        return AuthCallbackResult.WebToken(authToken = token, expiresInSeconds = expiresIn)
    }
}
