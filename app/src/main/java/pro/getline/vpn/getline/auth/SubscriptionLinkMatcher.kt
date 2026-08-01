package pro.getline.vpn.getline.auth

import android.net.Uri
import pro.getline.vpn.GetLineControlPlaneHostPolicy

/**
 * Canonical form of a subscription URL for account-ownership comparison.
 * Deliberately conservative: a false "no match" costs one dialog, a false
 * "match" silently binds a profile to a foreign subscription.
 */
object SubscriptionLinkMatcher {
    fun canonical(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = Uri.parse(url.trim())
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        val host = GetLineControlPlaneHostPolicy.canonicalizeHost(uri.host) ?: return null
        val port = uri.port
        val portPart = when {
            port == -1 || port == 443 -> ""
            else -> ":$port"
        }
        var path = uri.encodedPath.orEmpty()
        if (path.endsWith('/')) {
            path = path.dropLast(1)
        }
        val encodedQuery = uri.encodedQuery
        val queryPart = if (encodedQuery != null) "?$encodedQuery" else ""
        return "https://$host$portPart$path$queryPart"
    }

    fun matchesAny(source: String?, items: List<SubscriptionItem>): Boolean =
        findMatch(source, items) != null

    /**
     * First list item whose link canonically equals [source], or null.
     * Used so a secondary-subscription match reuses that item — not preferred.
     */
    fun findMatch(source: String?, items: List<SubscriptionItem>): SubscriptionItem? {
        val needle = canonical(source) ?: return null
        return items.firstOrNull { canonical(it.subscriptionLink) == needle }
    }
}
