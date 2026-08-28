package com.github.kr328.clash.service.localproxy

import android.net.Network
import kotlinx.coroutines.flow.Flow
import java.net.Inet4Address

/**
 * One approved LAN/hotspot IPv4 endpoint on the current [Network]. Internal to
 * the local-proxy runtime collaborator — never a product-facing type (see
 * plan Module boundary: results never contain an Android `Network`).
 *
 * [address] is what Mihomo's listener binds to and what a LAN client dials;
 * [network] only routes this process's own probe sockets to that address
 * instead of through GetLine's TUN. The two have different lifetimes: a Wi-Fi
 * reconnect that keeps the same DHCP lease produces a *new* [Network] for the
 * *same* [address], and the listener — being address-bound — survives it.
 *
 * [epoch] is what separates that case from an address that genuinely went away
 * and came back. It counts how many times the source has observed [address]
 * disappear, so a listener bound before the loss can be told apart from one
 * bound after it. Without it the two are indistinguishable at read time: the
 * change signal is conflated, so a fast enough stop/start — a hotspot restart
 * reuses its address — can leave only the final, apparently-unchanged state to
 * observe, and a dead listener would be treated as still serving.
 */
data class LocalLanProxyEndpoint(
    val address: Inet4Address,
    val network: Network,
    val epoch: Long = 0L,
)

/**
 * Source of the currently eligible endpoint, and of the signal that eligibility
 * may have changed.
 *
 * [LocalLanProxyRuntimeCoordinator] owns the policy that reacts to [changes];
 * this seam exists so that policy can be exercised without a real
 * `ConnectivityManager`. The production implementation is
 * [LocalLanProxyEndpointMonitor].
 */
interface LocalLanProxyEndpointSource {
    /**
     * The single eligible endpoint right now, or `null` when there is none —
     * including when there is more than one, which is ambiguity and fails
     * closed rather than picking a winner.
     */
    suspend fun currentEndpoint(): LocalLanProxyEndpoint?

    /**
     * Emits whenever the eligible endpoint may have changed. Carries no value:
     * consumers re-query [currentEndpoint] so they always act on the newest
     * state rather than on a possibly-stale event payload.
     */
    val changes: Flow<Unit>
}
