package com.github.kr328.clash.service.localproxy

import android.net.Network
import java.net.Inet4Address

/**
 * One approved LAN/hotspot IPv4 endpoint on the current [Network]. Internal to
 * the local-proxy runtime collaborator — never a product-facing type (see
 * plan Module boundary: results never contain an Android `Network`).
 */
data class LocalLanProxyEndpoint(
    val address: Inet4Address,
    val network: Network,
)

/**
 * Source of the currently eligible endpoint. Real Wi-Fi/hotspot observation
 * is a later step (endpoint monitor/policy); [LocalLanProxyRuntimeCoordinator]
 * only depends on this narrow seam so it can be exercised with an injected
 * fixture before that policy exists.
 */
fun interface LocalLanProxyEndpointSource {
    suspend fun currentEndpoint(): LocalLanProxyEndpoint?
}
