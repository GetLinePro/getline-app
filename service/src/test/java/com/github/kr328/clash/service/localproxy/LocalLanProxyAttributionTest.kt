package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The diff that decides what gets written to the epoch ledger.
 *
 * Its job is narrow and easy to get wrong in both directions: miss a
 * disappearance and a dead listener is treated as live, report one that did
 * not happen and a working proxy is torn down.
 */
class LocalLanProxyAttributionTest {
    private fun v4(literal: String): Inet4Address = InetAddress.getByName(literal) as Inet4Address

    private val a = v4("192.168.1.50")
    private val b = v4("192.168.1.77")
    private val hotspot = v4("10.135.213.166")

    private fun wifi(vararg pairs: Pair<String, Set<Inet4Address>>) =
        LocalLanProxyAttribution(byNetwork = pairs.toMap())

    @Test
    fun nothingChanged_reportsNothing() {
        val before = wifi("n1" to setOf(a))

        assertEquals(emptySet<Inet4Address>(), before.vanishedSince(before))
    }

    @Test
    fun networkGoneEntirely_reportsItsAddresses() {
        val before = wifi("n1" to setOf(a))
        val after = LocalLanProxyAttribution<String>()

        assertEquals(setOf(a), after.vanishedSince(before))
    }

    @Test
    fun addressSwappedWithinASurvivingNetwork_reportsTheOldOne() {
        // The onLinkPropertiesChanged case: the network never dropped.
        val before = wifi("n1" to setOf(a))
        val after = wifi("n1" to setOf(b))

        assertEquals(setOf(a), after.vanishedSince(before))
    }

    @Test
    fun addressAdded_reportsNothing() {
        val before = wifi("n1" to setOf(a))
        val after = wifi("n1" to setOf(a, b))

        assertEquals(emptySet<Inet4Address>(), after.vanishedSince(before))
    }

    @Test
    fun tetherStopped_reportsItsAddresses() {
        val before = LocalLanProxyAttribution<String>(tethered = mapOf("tether" to setOf(hotspot)))
        val after = LocalLanProxyAttribution<String>()

        assertEquals(setOf(hotspot), after.vanishedSince(before))
    }

    @Test
    fun sameAddressStillHeldByAnotherNetwork_hasNotVanished() {
        // Losing one network must not invalidate an address another still has.
        val before = wifi("n1" to setOf(a), "n2" to setOf(a))
        val after = wifi("n2" to setOf(a))

        assertEquals(emptySet<Inet4Address>(), after.vanishedSince(before))
    }

    @Test
    fun addressMovedBetweenSources_hasNotVanished() {
        // A rebind that hands the same address from the Wi-Fi path to the
        // tether path is continuity, not a loss.
        val before = wifi("n1" to setOf(hotspot))
        val after = LocalLanProxyAttribution<String>(tethered = mapOf("tether" to setOf(hotspot)))

        assertEquals(emptySet<Inet4Address>(), after.vanishedSince(before))
    }

    @Test
    fun losingEverything_reportsEverySource() {
        val before = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a)),
            tethered = mapOf("tether" to setOf(hotspot)),
        )
        val after = LocalLanProxyAttribution<String>()

        assertEquals(setOf(a, hotspot), after.vanishedSince(before))
    }

    @Test
    fun endpointsPairEveryAddressWithItsOwnNetwork() {
        // What a read is answered from. Each address must carry the network it
        // was actually seen on: a live address paired with a stale network is a
        // dead probe route.
        val attribution = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a)),
            tethered = mapOf("n2" to setOf(hotspot)),
        )

        assertEquals(
            setOf(ObservedEndpoint("n1", a), ObservedEndpoint("n2", hotspot)),
            attribution.endpoints.toSet(),
        )
    }

    @Test
    fun endpointsDeduplicateAnAddressVisibleThroughBothSources() {
        val attribution = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(hotspot)),
            tethered = mapOf("n1" to setOf(hotspot)),
        )

        assertEquals(listOf(ObservedEndpoint("n1", hotspot)), attribution.endpoints)
    }

    @Test
    fun theSameAddressOnTwoNetworks_staysTwoEndpoints() {
        // Genuine ambiguity: selection must be able to refuse it.
        val attribution = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a), "n2" to setOf(a)),
        )

        assertEquals(2, attribution.endpoints.size)
        assertNull(LocalLanProxyEndpointPolicy.selectSingle(attribution.endpoints))
    }

    @Test
    fun addressMovedBetweenNetworks_hasNotVanished() {
        val before = LocalLanProxyAttribution(byNetwork = mapOf("n1" to setOf(a)))
        val after = LocalLanProxyAttribution(byNetwork = mapOf("n2" to setOf(a)))

        assertEquals(emptySet<Inet4Address>(), after.vanishedSince(before))
    }
}
