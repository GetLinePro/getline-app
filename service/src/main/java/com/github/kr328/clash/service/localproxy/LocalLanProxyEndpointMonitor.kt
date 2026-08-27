package com.github.kr328.clash.service.localproxy

import android.app.Service
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.Inet4Address
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Observes which LAN IPv4 address, if any, the local proxy may bind to.
 *
 * This is the local-proxy module's own `ConnectivityManager` consumer, not a
 * branch in `NetworkObserveModule` — that module stays a general
 * core-networking concern with no local-proxy special case (see plan
 * Decisions). Nothing here decides *policy*: it reports the eligible endpoint
 * and signals change, and [LocalLanProxyRuntimeCoordinator] decides what a
 * change means for a running transaction.
 *
 * ## One definition of "a network we watch"
 *
 * Membership comes exclusively from the registered callback, never from a
 * separate re-derived predicate. That matters for safety, not tidiness: an
 * endpoint selected by a filter *wider* than the request would never receive
 * the loss/change signals the request generates, so its disappearance would
 * go unnoticed while a credentialed listener stayed bound to it. `NetworkRequest`
 * also carries default capabilities of its own (`NOT_RESTRICTED`, `TRUSTED`,
 * and further ones added in later API levels), so any hand-written mirror of
 * that predicate is a standing invitation to drift. Only [LinkProperties] are
 * read live, because addresses change within a network that stays the same.
 *
 * ## Hotspot
 *
 * Hotspot endpoints are **not** reported, so Enable refuses while a hotspot
 * address is the only one available. See the plan's Open questions: this is a
 * known unsatisfied requirement pending a device spike, not a settled design.
 */
class LocalLanProxyEndpointMonitor(service: Service) : LocalLanProxyEndpointSource, AutoCloseable {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!

    // Signal-only, conflated: a burst of callbacks during a reconnect should
    // wake the coordinator once, and it re-reads state itself rather than
    // trusting anything carried in the event.
    private val signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val changes: Flow<Unit> = signals

    /**
     * Exactly the networks currently satisfying [request]. The framework
     * delivers `onLost` when a network stops matching, so this set never
     * outgrows what the callback reports on.
     */
    private val matching: MutableSet<Network> =
        Collections.newSetFromMap(ConcurrentHashMap<Network, Boolean>())

    @Volatile
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            matching.add(network)
            signal("available")
        }

        override fun onLost(network: Network) {
            matching.remove(network)
            signal("lost")
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            matching.add(network)
            signal("link-properties")
        }
    }

    fun start() {
        if (registered) return

        try {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        } catch (e: Exception) {
            // Without the callback the coordinator never learns about address
            // loss, so currentEndpoint() must keep reporting nothing rather
            // than let enable proceed on an unwatched address.
            Log.w("LocalLanProxy endpoint monitor register failed", e)
        }
    }

    override fun close() {
        if (!registered) return
        registered = false

        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("LocalLanProxy endpoint monitor unregister failed", e)
        }

        matching.clear()
    }

    /**
     * Null whenever the monitor is not observing, or when the observed
     * candidates are anything other than exactly one eligible address.
     */
    override suspend fun currentEndpoint(): LocalLanProxyEndpoint? {
        if (!registered) return null

        return LocalLanProxyEndpointPolicy.selectSingle(candidates())
    }

    private fun candidates(): List<LocalLanProxyEndpoint> =
        matching.flatMap { network ->
            val link = connectivity.getLinkProperties(network)
                ?: return@flatMap emptyList<LocalLanProxyEndpoint>()

            link.linkAddresses
                .mapNotNull { it.address as? Inet4Address }
                .filter(LocalLanProxyEndpointPolicy::isEligibleListenAddress)
                .map { LocalLanProxyEndpoint(address = it, network = network) }
        }

    private fun signal(reason: String) {
        Log.d("LocalLanProxy endpoint monitor: $reason")

        signals.tryEmit(Unit)
    }
}
