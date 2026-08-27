package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the orphaned-apply race: a cleanup/rollback reload
 * that never confirms [com.github.kr328.clash.service.clash.module.ConfigurationReloadResult.Loaded]
 * (mailbox timeout, exception, or a Failed result) must fail-stop
 * unconditionally, without trusting a probe taken at that instant — because
 * `ConfigurationModule` processes one reload at a time, and an earlier
 * request that only timed out on the *caller* side (see
 * `ConfigurationReloadMailbox`) can still be stuck inside a real, in-flight
 * `Clash.load()` that completes later and reopens the listener after this
 * call already returned "confirmed gone".
 *
 * Also covers the address-loss rule: once the removal reload is confirmed, an
 * ambiguous probe against an address the phone no longer holds means the
 * endpoint is gone, not that the VPN should be stopped.
 */
class LocalLanProxyTeardownPolicyTest {
    @Test
    fun unconfirmedReload_neverProbes_andFailsStop() {
        var probed = false

        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = false, addressStillLocal = true) {
            probed = true
            // If the policy ever calls this, the test fixture would report
            // "nothing is listening yet" — exactly the false confirmation
            // the orphaned first apply could later contradict.
            LocalLanProxyProbeOutcome.Refused
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
        assertEquals("an unconfirmed reload must never even run the probe", false, probed)
    }

    @Test
    fun unconfirmedReload_failsStop_evenWhenAddressIsGone() {
        // Address loss excuses an ambiguous *probe*, never an unconfirmed
        // reload: the override may still be about to be re-applied.
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = false, addressStillLocal = false) {
            LocalLanProxyProbeOutcome.Ambiguous
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }

    @Test
    fun confirmedReload_refused_isConfirmedGone() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = true) {
            LocalLanProxyProbeOutcome.Refused
        }

        assertEquals(LocalLanProxyTeardownOutcome.ConfirmedGone, outcome)
    }

    @Test
    fun confirmedReload_occupiedByOther_isConfirmedGone() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = true) {
            LocalLanProxyProbeOutcome.OccupiedByOther
        }

        assertEquals(LocalLanProxyTeardownOutcome.ConfirmedGone, outcome)
    }

    @Test
    fun confirmedReload_stillAuthenticates_isFailStop() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = true) {
            LocalLanProxyProbeOutcome.Authenticated
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }

    @Test
    fun confirmedReload_stillAuthenticates_isFailStop_evenWhenAddressLooksGone() {
        // Reachable credentials beat any interface reading: reporting this
        // one inactive would leave a live, credentialed listener on the LAN.
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = false) {
            LocalLanProxyProbeOutcome.Authenticated
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }

    @Test
    fun confirmedReload_ambiguousProbe_whileAddressStillLocal_isFailStop() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = true) {
            LocalLanProxyProbeOutcome.Ambiguous
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }

    @Test
    fun confirmedReload_ambiguousProbe_afterAddressLoss_isConfirmedGone() {
        // Walking out of Wi-Fi range: the old address cannot answer
        // ECONNREFUSED because it is not assigned any more, so requiring
        // refusal here would stop the whole VPN on every disconnect.
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true, addressStillLocal = false) {
            LocalLanProxyProbeOutcome.Ambiguous
        }

        assertEquals(LocalLanProxyTeardownOutcome.ConfirmedGone, outcome)
    }
}
