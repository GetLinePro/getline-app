package com.github.kr328.clash.service.localproxy

import java.net.Inet4Address

/**
 * Pure eligibility rules for choosing the address the local proxy listens on.
 * Kept free of Android types so the decisions below are unit-testable;
 * [LocalLanProxyEndpointMonitor] supplies the observed candidates.
 */
internal object LocalLanProxyEndpointPolicy {
    /**
     * Whether [address] is a private LAN address this feature is allowed to
     * expose a credentialed listener on.
     *
     * Only RFC1918 (`10/8`, `172.16/12`, `192.168/16`) and CGNAT (`100.64/10`)
     * qualify. The product promise is "your own Wi-Fi network or phone
     * hotspot": binding to a *public* IPv4 — which a phone can genuinely hold
     * on some mobile/ISP networks — would put a password-gated proxy directly
     * on the internet, so an address outside these ranges is not an eligible
     * endpoint at all rather than a listener we harden afterwards.
     *
     * Loopback is excluded because a LAN client cannot reach it, and
     * link-local (`169.254/16`) because it is interface-scoped and is exactly
     * what the destination rule from step 3 refuses to forward to.
     */
    fun isEligibleListenAddress(address: Inet4Address): Boolean {
        val octets = address.address
        if (octets.size != 4) return false

        val a = octets[0].toInt() and 0xff
        val b = octets[1].toInt() and 0xff

        return when {
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 100 && b in 64..127 -> true
            else -> false
        }
    }

    /**
     * Exactly one candidate, or none.
     *
     * A phone that holds two eligible LAN addresses at once — Wi-Fi plus an
     * active hotspot, or two addresses on one interface — has no single
     * "the" endpoint. The plan requires one identified eligible endpoint, so
     * ambiguity fails closed here instead of being resolved by an ordering
     * rule the user cannot see or predict.
     */
    fun <T> selectSingle(candidates: List<T>): T? = candidates.singleOrNull()
}
