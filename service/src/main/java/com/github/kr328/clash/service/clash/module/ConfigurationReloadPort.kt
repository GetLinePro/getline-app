package com.github.kr328.clash.service.clash.module

import java.util.UUID

/**
 * Generic result of one config-reload attempt. No local-proxy (or any other
 * feature) model belongs here — [ConfigurationModule] stays unaware of why a
 * direct caller asked for a reload.
 */
sealed interface ConfigurationReloadResult {
    data class Loaded(val uuid: UUID) : ConfigurationReloadResult
    data class Failed(val message: String) : ConfigurationReloadResult
}

/**
 * Internal service-side seam: request a real config reload and await the
 * outcome of that specific attempt, distinct from [ConfigurationModule]'s
 * existing anonymous, conflated broadcast-triggered reload loop.
 *
 * Every call either completes with a [ConfigurationReloadResult] describing
 * the actual apply outcome, or fails exceptionally — it never hangs forever
 * and never silently observes an unrelated reload.
 */
interface ConfigurationReloadPort {
    suspend fun reloadAndAwait(): ConfigurationReloadResult
}
