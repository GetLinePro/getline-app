package pro.getline.vpn.getline.auth

import android.net.NetworkCapabilities
import com.github.kr328.clash.common.network.UnderlyingNetworkSelector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UnderlyingNetworkSelectorCapabilitiesTest {
    @Test
    fun validatedInternetOutsideVpn_isUsable() {
        assertTrue(UnderlyingNetworkSelector.isUsable(capabilities()))
    }

    @Test
    fun missingCapabilities_areRejected() {
        assertFalse(UnderlyingNetworkSelector.isUsable(null))
        assertFalse(
            UnderlyingNetworkSelector.isUsable(
                capabilities(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            ),
        )
        assertFalse(
            UnderlyingNetworkSelector.isUsable(
                capabilities(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            ),
        )
        assertFalse(
            UnderlyingNetworkSelector.isUsable(
                capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            ),
        )
    }

    @Test
    fun vpnTransport_isRejected_evenWhenCapabilitiesClaimNotVpn() {
        val capabilities = capabilities()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)

        assertFalse(UnderlyingNetworkSelector.isUsable(capabilities))
    }

    private fun capabilities(vararg excluded: Int): NetworkCapabilities {
        return ShadowNetworkCapabilities.newInstance().also { capabilities ->
            shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
            ).forEach { shadowOf(capabilities).addCapability(it) }
            excluded.forEach { shadowOf(capabilities).removeCapability(it) }
        }
    }
}
