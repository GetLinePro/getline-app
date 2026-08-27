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
 */
class LocalLanProxyTeardownPolicyTest {
    @Test
    fun unconfirmedReload_neverProbes_andFailsStop() {
        var probed = false

        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = false) {
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
    fun confirmedReload_refused_isConfirmedGone() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true) {
            LocalLanProxyProbeOutcome.Refused
        }

        assertEquals(LocalLanProxyTeardownOutcome.ConfirmedGone, outcome)
    }

    @Test
    fun confirmedReload_occupiedByOther_isConfirmedGone() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true) {
            LocalLanProxyProbeOutcome.OccupiedByOther
        }

        assertEquals(LocalLanProxyTeardownOutcome.ConfirmedGone, outcome)
    }

    @Test
    fun confirmedReload_stillAuthenticates_isFailStop() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true) {
            LocalLanProxyProbeOutcome.Authenticated
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }

    @Test
    fun confirmedReload_ambiguousProbe_isFailStop() {
        val outcome = LocalLanProxyTeardownPolicy.decide(reloadLoaded = true) {
            LocalLanProxyProbeOutcome.Ambiguous
        }

        assertEquals(LocalLanProxyTeardownOutcome.FailStop, outcome)
    }
}
