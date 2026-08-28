package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The poll's edge detector. The poll exists only because the hotspot network
 * produces no framework loss callback, so what matters here is that every real
 * transition is reported once and that a steady state is silent — a signal on
 * every tick would turn an event-driven service into a periodic waker.
 */
class LocalLanProxyChangeGateTest {
    private data class Endpoint(val address: String, val network: String)

    private val hotspot = Endpoint("10.135.213.166", "n-523096739853")

    @Test
    fun firstObservation_onlyEstablishesBaseline() {
        // The tether event that opened the poll window already signalled.
        val gate = LocalLanProxyChangeGate<Endpoint>()

        assertFalse(gate.observe(hotspot))
    }

    @Test
    fun unchangedValue_staysSilent() {
        val gate = LocalLanProxyChangeGate<Endpoint>()
        gate.observe(hotspot)

        repeat(5) {
            assertFalse(gate.observe(hotspot))
        }
    }

    @Test
    fun addressLoss_signalsOnce() {
        val gate = LocalLanProxyChangeGate<Endpoint>()
        gate.observe(hotspot)

        assertTrue(gate.observe(null))
        assertFalse(gate.observe(null))
    }

    @Test
    fun startupRace_resolvesAsNullToEndpointEdge() {
        // The tethered interface can be reported before its network is visible.
        val gate = LocalLanProxyChangeGate<Endpoint>()

        assertFalse(gate.observe(null))
        assertTrue(gate.observe(hotspot))
    }

    @Test
    fun sameAddressOnNewNetwork_signals() {
        // Observed on hotspot restart: the address and interface name were
        // reused, but the Network handle changed. The coordinator needs this to
        // refresh its probe route.
        val gate = LocalLanProxyChangeGate<Endpoint>()
        gate.observe(hotspot)

        assertTrue(gate.observe(hotspot.copy(network = "n-531686674445")))
    }

    @Test
    fun addressChangeOnSameNetwork_signals() {
        val gate = LocalLanProxyChangeGate<Endpoint>()
        gate.observe(hotspot)

        assertTrue(gate.observe(hotspot.copy(address = "10.135.213.170")))
    }

    @Test
    fun returningToPreviousValue_signalsBothTransitions() {
        val gate = LocalLanProxyChangeGate<Endpoint>()
        gate.observe(hotspot)

        assertTrue(gate.observe(null))
        assertTrue(gate.observe(hotspot))
    }
}
