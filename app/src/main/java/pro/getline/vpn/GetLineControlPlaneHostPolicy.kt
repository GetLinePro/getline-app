package pro.getline.vpn

import android.net.Uri
import com.github.kr328.clash.BuildConfig
import pro.getline.vpn.getline.auth.GetLineAuthException

/**
 * Allowed product hosts for GetLine **control plane** (auth / session / portal /
 * subscription import URL from API).
 *
 * Isolation is environment-scoped (`prod` vs `e2e` flavor). It does **not**
 * apply to Mihomo VPN traffic or arbitrary proxy endpoints after profile import.
 *
 * E2E allowlist is strict so a misconfigured mock or response cannot open or
 * call production RWP/Auth. Prod rejects stage and other known GetLine hosts
 * outside the production allowlist; third-party OAuth hosts (e.g. Google) are
 * still allowed for browser launch on prod only.
 *
 * Hostnames are [canonicalizeHost]d (lowercase, strip trailing FQDN dots) so
 * `auth.stage.getline.pro.` cannot bypass GetLine-family classification.
 */
object GetLineControlPlaneHostPolicy {
    val e2eAllowedHosts: Set<String> = setOf(
        "app.stage.getline.pro",
        "auth.stage.getline.pro",
    )

    val prodAllowedHosts: Set<String> = setOf(
        "app.getline.pro",
        // Callback-only host: serves Digital Asset Links and a static completion
        // page, never the SPA or a web manifest, so no WebAPK can claim it.
        "auth.getline.pro",
    )

    /** True when the app was built with environment flavor `e2e`. */
    val isE2e: Boolean
        get() = BuildConfig.FLAVOR_environment == "e2e"

    fun allowedHosts(): Set<String> =
        if (isE2e) e2eAllowedHosts else prodAllowedHosts

    /**
     * DNS FQDN form may include a trailing root dot (`example.com.`).
     * Strip it and lowercase so allowlist / family checks cannot be bypassed.
     */
    fun canonicalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null
        var normalized = host.trim().lowercase()
        while (normalized.endsWith('.')) {
            normalized = normalized.dropLast(1)
        }
        return normalized.takeIf { it.isNotEmpty() }
    }

    /**
     * Strict product-host check for API origin, portal and callback.
     *
     * Not for `subscription_link` — that host belongs to RWP and is not in this
     * allowlist; see [isAllowedSubscriptionHost].
     */
    fun isAllowedProductHost(host: String?): Boolean {
        val canonical = canonicalizeHost(host) ?: return false
        return allowedHosts().any { it == canonical }
    }

    /**
     * Browser launch URL host.
     *
     * - **e2e:** only stage product hosts (mock Google lives on auth.stage).
     * - **prod:** any non-GetLine host (OAuth providers) plus prod allowlist;
     *   rejects stage / bot / other GetLine hosts outside the allowlist.
     */
    fun isAllowedBrowserLaunchHost(host: String?): Boolean {
        val canonical = canonicalizeHost(host) ?: return false
        if (isE2e) {
            return isAllowedProductHost(canonical)
        }
        if (isGetLineFamilyHost(canonical)) {
            return isAllowedProductHost(canonical)
        }
        return true
    }

    fun isAllowedProductHttpsUrl(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        return isAllowedProductHost(uri.host)
    }

    fun isAllowedBrowserLaunchUrl(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        return isAllowedBrowserLaunchHost(uri.host)
    }

    fun requireProductHost(host: String?, label: String = "host") {
        if (!isAllowedProductHost(host)) {
            throw GetLineAuthException.Protocol(
                "$label not allowed for this environment",
            )
        }
    }

    fun requireProductHttpsUrl(url: String?, label: String = "url") {
        val uri = parseHttpsUri(url)
            ?: throw GetLineAuthException.Protocol(
                "$label must be https with a host",
            )
        requireProductHost(uri.host, label)
    }

    fun requireBrowserLaunchUrl(url: String?) {
        val uri = parseHttpsUri(url)
            ?: throw GetLineAuthException.Protocol("auth_url must be https")
        if (!isAllowedBrowserLaunchHost(uri.host)) {
            throw GetLineAuthException.Protocol(
                "auth_url host not allowed for this environment",
            )
        }
    }

    fun requireApiOrigin(origin: String) {
        requireProductHttpsUrl(origin, "API origin")
    }

    /**
     * Subscription import URL returned by the control-plane API.
     *
     * The point of this check is **environment isolation**, not a narrow
     * allowlist: RWP hands out its own host, which is not one of the two
     * control-plane hosts, so [isAllowedProductHost] rejected every real
     * production link (observed 2026-07-30: import failed with
     * `subscription_link not allowed for this environment` right after a
     * successful session).
     *
     * - **e2e:** strict [e2eAllowedHosts] — a misconfigured mock must never be
     *   able to hand out a production URL.
     * - **prod:** any GetLine-family host except stage.
     */
    fun isAllowedSubscriptionHost(host: String?): Boolean {
        val canonical = canonicalizeHost(host) ?: return false
        if (isE2e) {
            return isAllowedProductHost(canonical)
        }
        return isGetLineFamilyHost(canonical) && !isStageHost(canonical)
    }

    /**
     * Predicate form of [requireSubscriptionUrl] for UI validators (manual entry /
     * QR) that need a boolean without throwing.
     */
    fun isAllowedSubscriptionUrl(url: String?): Boolean {
        val uri = parseHttpsUri(url) ?: return false
        return isAllowedSubscriptionHost(uri.host)
    }

    fun requireSubscriptionUrl(url: String?) {
        if (isAllowedSubscriptionUrl(url)) return
        // Preserve distinct messages for callers that surface Protocol text.
        if (parseHttpsUri(url) == null) {
            throw GetLineAuthException.Protocol(
                "subscription_link must be https with a host",
            )
        }
        throw GetLineAuthException.Protocol(
            "subscription_link host not allowed for this environment",
        )
    }

    /** Hostname is under getline.pro (including the apex). */
    fun isGetLineFamilyHost(host: String?): Boolean {
        val normalized = canonicalizeHost(host) ?: return false
        return normalized == "getline.pro" || normalized.endsWith(".getline.pro")
    }

    /** Hostname is a stage host of the GetLine family. */
    fun isStageHost(host: String?): Boolean {
        val normalized = canonicalizeHost(host) ?: return false
        return normalized == "stage.getline.pro" ||
            normalized.endsWith(".stage.getline.pro")
    }

    private fun parseHttpsUri(url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        val uri = Uri.parse(url.trim())
        if (!"https".equals(uri.scheme, ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        if (canonicalizeHost(uri.host) == null) return null
        return uri
    }
}
