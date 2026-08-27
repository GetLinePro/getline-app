package com.github.kr328.clash.service.localproxy

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * A single local interface reduced to what the locality decision needs.
 * Exists so [LocalLanProxyAddressLocality.isAssignedLocally] can be tested
 * without real [NetworkInterface] instances, which cannot be constructed.
 */
internal data class LocalInterfaceSnapshot(
    val isUp: Boolean,
    val isLoopback: Boolean,
    val addresses: List<InetAddress>,
)

/**
 * Answers one question for the teardown decision: does this phone still hold
 * the address the local proxy was listening on?
 *
 * This matters because closure proof is asymmetric. While the address is still
 * assigned, a probe that neither authenticates nor is refused is genuinely
 * ambiguous and must fail closed. Once the address is gone, no probe can ever
 * produce `ECONNREFUSED` from it — the socket fails with `EADDRNOTAVAIL`,
 * `ENETUNREACH` or a timeout instead — so treating that ambiguity as
 * fail-closed would stop the whole VPN every time the user walks out of Wi-Fi
 * range. See the plan's address-loss rule in Decisions.
 *
 * The check deliberately does not try to exclude GetLine's own TUN interface.
 * If the TUN somehow carried the same address the answer would be "still
 * local", which is the conservative direction.
 */
internal object LocalLanProxyAddressLocality {
    /** Pure decision over an already-collected interface list. */
    fun isAssignedLocally(address: Inet4Address, interfaces: List<LocalInterfaceSnapshot>): Boolean =
        interfaces.any { iface ->
            iface.isUp && !iface.isLoopback && iface.addresses.any { it == address }
        }

    /** Live enumeration. Any enumeration failure reads as "still local", which fails closed. */
    fun isAssignedLocally(address: Inet4Address): Boolean {
        val interfaces = currentInterfaces() ?: return true

        return isAssignedLocally(address, interfaces)
    }

    private fun currentInterfaces(): List<LocalInterfaceSnapshot>? = try {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.map { iface ->
                LocalInterfaceSnapshot(
                    isUp = runCatching { iface.isUp }.getOrDefault(true),
                    isLoopback = runCatching { iface.isLoopback }.getOrDefault(false),
                    addresses = iface.inetAddresses.toList(),
                )
            }
    } catch (e: SocketException) {
        null
    }
}
