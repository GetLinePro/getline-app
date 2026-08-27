package com.github.kr328.clash.service

import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeHolder
import com.github.kr328.clash.service.remote.ILocalLanProxyRuntime
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeConfig
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult

/**
 * Thin cross-process seam onto whichever coordinator [LocalLanProxyRuntimeHolder]
 * currently holds. Owns no state itself and makes no policy decision beyond
 * "no VPN session is running" — everything else is the coordinator's job.
 */
class LocalLanProxyRuntimeManager : ILocalLanProxyRuntime {
    override suspend fun enable(config: LocalLanProxyRuntimeConfig): LocalLanProxyRuntimeResult {
        val coordinator = LocalLanProxyRuntimeHolder.coordinator
            ?: return LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)

        return coordinator.enable(config)
    }

    override suspend fun disable(): LocalLanProxyRuntimeResult {
        val coordinator = LocalLanProxyRuntimeHolder.coordinator
            ?: return LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.VpnUnavailable)

        return coordinator.disable()
    }
}
