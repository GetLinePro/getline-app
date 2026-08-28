package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Locality is the input that lets an ambiguous teardown probe mean "gone"
 * instead of "stop the VPN" (see [LocalLanProxyTeardownPolicy]), so what
 * counts as still-assigned matters.
 */
class LocalLanProxyAddressLocalityTest {
    private fun v4(literal: String): Inet4Address = InetAddress.getByName(literal) as Inet4Address

    private fun iface(
        vararg addresses: String,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
    ) = LocalInterfaceSnapshot(
        isUp = isUp,
        isLoopback = isLoopback,
        addresses = addresses.map { InetAddress.getByName(it) },
    )

    @Test
    fun assignedToAnUpInterface_isLocal() {
        val local = LocalLanProxyAddressLocality.isAssignedLocally(
            v4("192.168.1.50"),
            listOf(iface("fe80::1", "192.168.1.50")),
        )

        assertEquals(true, local)
    }

    @Test
    fun noInterfaceHoldsIt_isNotLocal() {
        val local = LocalLanProxyAddressLocality.isAssignedLocally(
            v4("192.168.1.50"),
            listOf(iface("192.168.1.51"), iface("10.0.0.2")),
        )

        assertEquals(false, local)
    }

    @Test
    fun emptyInterfaceList_isNotLocal() {
        val local = LocalLanProxyAddressLocality.isAssignedLocally(v4("192.168.1.50"), emptyList())

        assertEquals(false, local)
    }

    @Test
    fun downInterface_doesNotCount() {
        // The address is configured but the link is gone, which is exactly
        // the "cannot answer ECONNREFUSED any more" situation.
        val local = LocalLanProxyAddressLocality.isAssignedLocally(
            v4("192.168.1.50"),
            listOf(iface("192.168.1.50", isUp = false)),
        )

        assertEquals(false, local)
    }

    @Test
    fun loopbackInterface_doesNotCount() {
        val local = LocalLanProxyAddressLocality.isAssignedLocally(
            v4("127.0.0.1"),
            listOf(iface("127.0.0.1", isLoopback = true)),
        )

        assertEquals(false, local)
    }
}
