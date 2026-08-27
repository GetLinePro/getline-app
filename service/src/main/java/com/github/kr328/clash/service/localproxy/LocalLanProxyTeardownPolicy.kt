package com.github.kr328.clash.service.localproxy

internal sealed interface LocalLanProxyTeardownOutcome {
    /** The transaction's credentials are confirmed to no longer authenticate. */
    object ConfirmedGone : LocalLanProxyTeardownOutcome

    /** Teardown could not be positively confirmed; `TunService` must fail-stop. */
    object FailStop : LocalLanProxyTeardownOutcome
}

/**
 * Pure decision for whether GetLine's listener is confirmed gone after a
 * clear-override-and-reload attempt, or whether the caller must fail-stop
 * `TunService` instead.
 *
 * [reloadLoaded] must reflect only a *confirmed* [ConfigurationReloadResult.Loaded][com.github.kr328.clash.service.clash.module.ConfigurationReloadResult.Loaded]
 * outcome — never a timeout, an exception, or a [Failed][com.github.kr328.clash.service.clash.module.ConfigurationReloadResult.Failed]
 * result. `ConfigurationModule` runs one reload at a time; a request that
 * merely timed out on the *caller* side is not the same as one the module
 * finished processing. An earlier, still in-flight `Clash.load()` (for
 * example the original enable's own reload, abandoned past its own timeout)
 * can complete later and reopen the listener after this call already
 * returned — a probe taken right now cannot rule that out, so when
 * [reloadLoaded] is false this fails closed unconditionally and never even
 * consults [probe], regardless of what it would currently show.
 *
 * Only once the reload is confirmed applied does the live probe become
 * trustworthy: [probe] is the source of truth for whether the configured
 * credentials still authenticate.
 *
 * [addressStillLocal] resolves the one case the probe cannot decide on its
 * own. An address the phone no longer holds can never answer
 * `ECONNREFUSED` — the connect fails with `EADDRNOTAVAIL`, `ENETUNREACH` or a
 * timeout, all of which classify as
 * [Ambiguous][LocalLanProxyProbeOutcome.Ambiguous]. Once the correlated
 * removal reload has succeeded, that is enough to call the endpoint gone: it
 * is no longer exposed, and losing Wi-Fi must not stop the VPN merely because
 * refusal became unobservable. While the address *is* still assigned, the same
 * ambiguity keeps its original meaning and fails closed.
 *
 * Successful authentication is never excused by address loss: if the
 * transaction's credentials still work, GetLine's listener is demonstrably
 * reachable and reporting it gone would be a lie regardless of what the
 * interface list says.
 */
internal object LocalLanProxyTeardownPolicy {
    fun decide(
        reloadLoaded: Boolean,
        addressStillLocal: Boolean,
        probe: () -> LocalLanProxyProbeOutcome,
    ): LocalLanProxyTeardownOutcome {
        if (!reloadLoaded) return LocalLanProxyTeardownOutcome.FailStop

        return when (probe()) {
            LocalLanProxyProbeOutcome.Refused, LocalLanProxyProbeOutcome.OccupiedByOther ->
                LocalLanProxyTeardownOutcome.ConfirmedGone
            LocalLanProxyProbeOutcome.Authenticated ->
                LocalLanProxyTeardownOutcome.FailStop
            LocalLanProxyProbeOutcome.Ambiguous ->
                if (addressStillLocal) {
                    LocalLanProxyTeardownOutcome.FailStop
                } else {
                    LocalLanProxyTeardownOutcome.ConfirmedGone
                }
        }
    }
}
