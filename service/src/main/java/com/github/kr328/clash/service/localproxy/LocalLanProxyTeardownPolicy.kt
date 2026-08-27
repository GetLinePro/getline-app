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
 */
internal object LocalLanProxyTeardownPolicy {
    fun decide(reloadLoaded: Boolean, probe: () -> LocalLanProxyProbeOutcome): LocalLanProxyTeardownOutcome {
        if (!reloadLoaded) return LocalLanProxyTeardownOutcome.FailStop

        return when (probe()) {
            LocalLanProxyProbeOutcome.Refused, LocalLanProxyProbeOutcome.OccupiedByOther ->
                LocalLanProxyTeardownOutcome.ConfirmedGone
            LocalLanProxyProbeOutcome.Authenticated, LocalLanProxyProbeOutcome.Ambiguous ->
                LocalLanProxyTeardownOutcome.FailStop
        }
    }
}
