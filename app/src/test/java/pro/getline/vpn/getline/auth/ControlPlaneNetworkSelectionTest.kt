package pro.getline.vpn.getline.auth

import com.github.kr328.clash.common.network.UnderlyingNetworkSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Control-plane HTTP must not ride the VPN tunnel when TunService is up.
 */
class ControlPlaneNetworkSelectionTest {

    @Test
    fun pickUnderlying_prefersActiveWhenUsable() {
        val picked = UnderlyingNetworkSelector.pick(
            active = "active",
            activeUsable = true,
            all = listOf("active", "other"),
            isUsable = { it == "active" || it == "other" },
        )
        assertEquals("active", picked)
    }

    @Test
    fun pickUnderlying_skipsUnusableActiveAndUsesUnderlying() {
        val picked = UnderlyingNetworkSelector.pick(
            active = "vpn",
            activeUsable = false,
            all = listOf("vpn", "cell"),
            isUsable = { it == "cell" },
        )
        assertEquals("cell", picked)
    }

    @Test
    fun pickUnderlying_nullWhenOnlyVpn() {
        val picked = UnderlyingNetworkSelector.pick(
            active = "vpn",
            activeUsable = false,
            all = listOf("vpn"),
            isUsable = { false },
        )
        assertNull(picked)
    }

    @Test
    fun pickUnderlying_nullActiveFallsThroughToAll() {
        val picked = UnderlyingNetworkSelector.pick(
            active = null,
            activeUsable = false,
            all = listOf("cell"),
            isUsable = { it == "cell" },
        )
        assertEquals("cell", picked)
    }
}
