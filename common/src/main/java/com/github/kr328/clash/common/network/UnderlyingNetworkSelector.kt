package com.github.kr328.clash.common.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Selects an Android network that cannot re-enter a VPN tunnel. */
object UnderlyingNetworkSelector {
    /**
     * INTERNET only means configured for internet. VALIDATED excludes captive or
     * currently unusable networks; NOT_VPN and the transport check exclude tunnels.
     */
    fun isUsable(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            return false
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return false
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            return false
        }
        return true
    }

    /** Pure selection: prefer [active] when usable, else the first usable entry. */
    fun <T> pick(
        active: T?,
        activeUsable: Boolean,
        all: List<T>,
        isUsable: (T) -> Boolean,
    ): T? {
        if (active != null && activeUsable) return active
        return all.firstOrNull(isUsable)
    }

    /** Prefer the active network when it is underlying; otherwise scan all networks. */
    fun pickNetwork(connectivity: ConnectivityManager): Network? {
        fun usable(network: Network): Boolean =
            isUsable(connectivity.getNetworkCapabilities(network))

        val active = connectivity.activeNetwork
        @Suppress("DEPRECATION")
        val all = connectivity.allNetworks.toList()
        return pick(
            active = active,
            activeUsable = active != null && usable(active),
            all = all,
            isUsable = ::usable,
        )
    }
}
