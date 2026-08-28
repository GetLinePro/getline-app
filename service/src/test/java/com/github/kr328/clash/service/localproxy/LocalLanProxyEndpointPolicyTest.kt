package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The eligibility rules that decide what a credentialed listener may be bound
 * to. The interesting cases are the refusals: a public address would put the
 * proxy on the internet, and two candidates mean there is no single endpoint
 * to identify.
 */
class LocalLanProxyEndpointPolicyTest {
    private fun v4(literal: String): Inet4Address = InetAddress.getByName(literal) as Inet4Address

    @Test
    fun privateRanges_areEligible() {
        listOf("10.0.0.5", "10.255.255.254", "172.16.3.4", "172.31.9.9", "192.168.1.50", "100.64.0.1")
            .forEach {
                assertTrue(it, LocalLanProxyEndpointPolicy.isEligibleListenAddress(v4(it)))
            }
    }

    @Test
    fun publicAddresses_areNotEligible() {
        // A phone really can hold one of these on some mobile/ISP networks;
        // binding there would expose the proxy beyond the LAN.
        listOf("8.8.8.8", "1.1.1.1", "203.0.113.7", "172.32.0.1", "172.15.0.1", "192.169.1.1")
            .forEach {
                assertEquals(it, false, LocalLanProxyEndpointPolicy.isEligibleListenAddress(v4(it)))
            }
    }

    @Test
    fun loopbackAndLinkLocal_areNotEligible() {
        // Loopback is unreachable for a LAN client; link-local is
        // interface-scoped and is what the step-3 destination rule refuses.
        listOf("127.0.0.1", "169.254.10.10")
            .forEach {
                assertEquals(it, false, LocalLanProxyEndpointPolicy.isEligibleListenAddress(v4(it)))
            }
    }

    @Test
    fun singleCandidate_isSelected() {
        assertEquals("a", LocalLanProxyEndpointPolicy.selectSingle(listOf("a")))
    }

    @Test
    fun noCandidate_selectsNothing() {
        assertNull(LocalLanProxyEndpointPolicy.selectSingle(emptyList<String>()))
    }

    @Test
    fun ambiguousCandidates_selectNothing() {
        // Wi-Fi plus hotspot, or two addresses on one interface: there is no
        // "the" endpoint, so this fails closed rather than guessing.
        assertNull(LocalLanProxyEndpointPolicy.selectSingle(listOf("a", "b")))
    }
}
