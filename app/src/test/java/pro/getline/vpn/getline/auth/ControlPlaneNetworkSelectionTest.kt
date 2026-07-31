package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Control-plane HTTP must not ride the VPN tunnel when TunService is up.
 */
class ControlPlaneNetworkSelectionTest {

    @Test
    fun pickUnderlying_prefersActiveWhenUsable() {
        val picked = RwpGetLineAuthApi.pickUnderlying(
            active = "active",
            activeUsable = true,
            all = listOf("active", "other"),
            isUsable = { it == "active" || it == "other" },
        )
        assertEquals("active", picked)
    }

    @Test
    fun pickUnderlying_skipsUnusableActiveAndUsesUnderlying() {
        val picked = RwpGetLineAuthApi.pickUnderlying(
            active = "vpn",
            activeUsable = false,
            all = listOf("vpn", "cell"),
            isUsable = { it == "cell" },
        )
        assertEquals("cell", picked)
    }

    @Test
    fun pickUnderlying_nullWhenOnlyVpn() {
        val picked = RwpGetLineAuthApi.pickUnderlying(
            active = "vpn",
            activeUsable = false,
            all = listOf("vpn"),
            isUsable = { false },
        )
        assertNull(picked)
    }

    @Test
    fun pickUnderlying_nullActiveFallsThroughToAll() {
        val picked = RwpGetLineAuthApi.pickUnderlying(
            active = null,
            activeUsable = false,
            all = listOf("cell"),
            isUsable = { it == "cell" },
        )
        assertEquals("cell", picked)
    }
}
