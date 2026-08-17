package com.github.kr328.clash.service.clash.module

/**
 * A failed [com.github.kr328.clash.core.Clash.load] before ApplyConfig leaves
 * the previous runtime intact. Killing TUN is only required when nothing has
 * loaded yet (VPN start). A later reload must not take the tunnel down.
 */
internal object ConfigurationLoadPolicy {
    fun abortRuntime(hasSuccessfulLoad: Boolean): Boolean = !hasSuccessfulLoad
}
