package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Attribution and loss counts as one value.
 *
 * The endpoint identity is `(address, network, epoch)`. These tests exist
 * because assembling it from separately-read parts admits pairings that never
 * existed — an old route wearing a new epoch reads to the coordinator as "the
 * address came back", and sends it to probe a route that is already dead.
 */
class LocalLanProxyObservationTest {
    private fun v4(literal: String): Inet4Address = InetAddress.getByName(literal) as Inet4Address

    private val a = v4("10.135.213.166")
    private val b = v4("192.168.1.50")

    private fun observing(vararg pairs: Pair<String, Set<Inet4Address>>) =
        LocalLanProxyAttribution(byNetwork = pairs.toMap())

    @Test
    fun unseenAddress_startsAtZero() {
        assertEquals(0L, LocalLanProxyObservation<String>().epochOf(a))
    }

    @Test
    fun vanishingBumpsTheEpoch_andItSurvivesTheReturn() {
        // The hotspot restart: by the time anyone reads, the address is back
        // and looks untouched. The count is the only surviving evidence.
        val gone = LocalLanProxyObservation<String>()
            .observing(observing("n1" to setOf(a)))
            .observing(LocalLanProxyAttribution())

        assertEquals(1L, gone.epochOf(a))

        val returned = gone.observing(observing("n2" to setOf(a)))

        assertEquals(1L, returned.epochOf(a))
    }

    @Test
    fun repeatedLosses_accumulate() {
        var observation = LocalLanProxyObservation<String>()

        repeat(3) {
            observation = observation
                .observing(observing("n1" to setOf(a)))
                .observing(LocalLanProxyAttribution())
        }

        assertEquals(3L, observation.epochOf(a))
    }

    @Test
    fun lossIsScopedToTheAddressesThatWentAway() {
        // Losing Wi-Fi must not invalidate a listener bound to the hotspot.
        val observation = LocalLanProxyObservation<String>()
            .observing(observing("wifi" to setOf(b), "tether" to setOf(a)))
            .observing(observing("tether" to setOf(a)))

        assertEquals(1L, observation.epochOf(b))
        assertEquals(0L, observation.epochOf(a))
    }

    @Test
    fun movingBetweenNetworks_isNotALoss() {
        val observation = LocalLanProxyObservation<String>()
            .observing(observing("n1" to setOf(a)))
            .observing(observing("n2" to setOf(a)))

        assertEquals(0L, observation.epochOf(a))
    }

    @Test
    fun selectionCarriesTheEpochOfTheSameSnapshot() {
        val observation = LocalLanProxyObservation<String>()
            .observing(observing("n1" to setOf(a)))
            .observing(LocalLanProxyAttribution())
            .observing(observing("n2" to setOf(a)))

        assertEquals(LocalLanProxySelectedEndpoint("n2", a, 1L), observation.selected())
    }

    @Test
    fun anEarlierSnapshotStaysInternallyConsistent() {
        // The barrier case, made unrepresentable rather than unlikely: hold a
        // snapshot, let the world move on, and the held value still describes
        // one coherent moment — never the old route with the new epoch.
        val before = LocalLanProxyObservation<String>().observing(observing("n1" to setOf(a)))

        val after = before
            .observing(LocalLanProxyAttribution())
            .observing(observing("n2" to setOf(a)))

        assertEquals(LocalLanProxySelectedEndpoint("n1", a, 0L), before.selected())
        assertEquals(LocalLanProxySelectedEndpoint("n2", a, 1L), after.selected())
        assertNotEquals(before.selected()!!.epoch, after.selected()!!.epoch)
    }

    @Test
    fun ambiguityStillFailsClosed() {
        val observation = LocalLanProxyObservation<String>()
            .observing(observing("n1" to setOf(a), "n2" to setOf(b)))

        assertNull(observation.selected())
    }

    @Test
    fun noCandidates_selectsNothing() {
        assertNull(LocalLanProxyObservation<String>().selected())
    }

    @Test
    fun tetherStopBumpsEpoch_evenWhenWifiStillListsTheSamePair() {
        // The non-disjoint-sources case. On an OEM that delivers the tethered
        // network through the Wi-Fi callback as well, clearing `tethered`
        // leaves an exact duplicate in `byNetwork`, so a flat address diff sees
        // nothing leave. The tether callback is authoritative and says so
        // outright.
        val both = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a)),
            tethered = mapOf("n1" to setOf(a)),
        )
        val observation = LocalLanProxyObservation<String>().observing(both)

        assertEquals(0L, observation.epochOf(a))

        val stopped = observation.observing(
            next = both.copy(tethered = emptyMap()),
            forciblyLost = both.tetheredAddresses,
        )

        assertEquals(1L, stopped.epochOf(a))
    }

    @Test
    fun forcedLossSurvivesAFastRestartThatOutrunsOnLost() {
        // The user-visible outcome epoch exists for: the hotspot comes back on
        // a new Network before onLost fires for the old one, so the address is
        // continuously present in some source and the late onLost bumps
        // nothing. The forced loss must already be on the books.
        val both = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a)),
            tethered = mapOf("n1" to setOf(a)),
        )

        val restarted = LocalLanProxyObservation<String>()
            .observing(both)
            .observing(both.copy(tethered = emptyMap()), forciblyLost = both.tetheredAddresses)
            .observing(
                LocalLanProxyAttribution(
                    byNetwork = mapOf("n1" to setOf(a)),
                    tethered = mapOf("n2" to setOf(a)),
                ),
            )
            .observing(LocalLanProxyAttribution(tethered = mapOf("n2" to setOf(a))))

        assertEquals(1L, restarted.epochOf(a))
        assertEquals(LocalLanProxySelectedEndpoint("n2", a, 1L), restarted.selected())
    }

    @Test
    fun withoutForcing_theDuplicateWouldHideTheLoss() {
        // Pins that the forcing is load-bearing: the same sequence relying on
        // the diff alone never books the disappearance.
        val both = LocalLanProxyAttribution(
            byNetwork = mapOf("n1" to setOf(a)),
            tethered = mapOf("n1" to setOf(a)),
        )

        val stopped = LocalLanProxyObservation<String>()
            .observing(both)
            .observing(both.copy(tethered = emptyMap()))

        assertEquals(0L, stopped.epochOf(a))
    }

    @Test
    fun forcedLossNeverInventsOneForAnUnobservedAddress() {
        val observation = LocalLanProxyObservation<String>()
            .observing(observing("n1" to setOf(a)), forciblyLost = setOf(b))

        assertEquals(0L, observation.epochOf(b))
    }
}
