package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The hotspot identity join. The device spike established that the system
 * names the tethered interface (`wlan2`) and that the same name appears as a
 * network's `LinkProperties.interfaceName`; these tests pin the rule that only
 * an *exact* system-supplied name is ever trusted, since the alternative — a
 * name or address heuristic — is what the API 34 result ruled out.
 */
class LocalLanProxyTetherReconcilerTest {
    private fun v4(literal: String): Inet4Address = InetAddress.getByName(literal) as Inet4Address

    private fun network(
        id: String,
        interfaceName: String?,
        vararg addresses: String,
    ) = ObservedNetwork(
        network = id,
        interfaceName = interfaceName,
        addresses = addresses.map(::v4),
    )

    @Test
    fun exactNameMatch_yieldsCandidate() {
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(network("n-hotspot", "wlan2", "10.135.213.166")),
        )

        assertEquals(listOf(ObservedEndpoint("n-hotspot", v4("10.135.213.166"))), candidates)
    }

    @Test
    fun emptyTetherSet_yieldsNothing_evenWhenNetworkStillPresent() {
        // Hotspot stopped: the network may linger for a moment, but with no
        // tethered interface reported there is no trusted hotspot identity.
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = emptySet(),
            networks = listOf(network("n-hotspot", "wlan2", "10.135.213.166")),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun otherInterfaces_areNotCandidates() {
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(
                network("n-wifi", "wlan0", "192.168.1.50"),
                network("n-cell", "rmnet0", "10.20.30.40"),
            ),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun nameMatchIsExact_notAPrefix() {
        // "wlan2" must not be satisfied by "wlan" or "wlan22"; a substring rule
        // would silently re-introduce the interface guess.
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(
                network("n-short", "wlan", "10.1.1.1"),
                network("n-long", "wlan22", "10.2.2.2"),
            ),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun missingInterfaceName_neverMatches() {
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(network("n-unnamed", null, "10.135.213.166")),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun ineligibleAddressOnTetheredInterface_isExcluded() {
        // Eligibility still applies after the join: a public address is not
        // made safe by arriving on a trusted interface.
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(network("n-hotspot", "wlan2", "203.0.113.7")),
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun twoEligibleAddresses_staySeparateCandidates_soSelectionCanRefuse() {
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(network("n-hotspot", "wlan2", "10.135.213.166", "192.168.43.1")),
        )

        assertEquals(2, candidates.size)
        // Ambiguity has to reach the selection rule, which fails closed.
        assertEquals(null, LocalLanProxyEndpointPolicy.selectSingle(candidates))
    }

    @Test
    fun sameEndpointReportedTwice_isDeduplicated() {
        val candidates = LocalLanProxyTetherReconciler.candidates(
            tetheredNames = setOf("wlan2"),
            networks = listOf(
                network("n-hotspot", "wlan2", "10.135.213.166"),
                network("n-hotspot", "wlan2", "10.135.213.166"),
            ),
        )

        assertEquals(1, candidates.size)
    }
}
