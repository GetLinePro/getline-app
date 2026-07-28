package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainProxyGroupPolicyTest {
    @Test
    fun empty_returnsNull() {
        assertNull(MainProxyGroupPolicy.resolveName(emptyList()))
    }

    @Test
    fun prefersKnownProductName_overListOrder() {
        val names = listOf("YOUTUBE", "DISCORD.EXE", "VPN", "GERMANY")
        assertEquals("VPN", MainProxyGroupPolicy.resolveName(names))
    }

    @Test
    fun prefersProxy_whenVpnMissing() {
        val names = listOf("AUTO", "PROXY", "OTHER")
        assertEquals("PROXY", MainProxyGroupPolicy.resolveName(names))
    }

    @Test
    fun caseInsensitivePreferred() {
        val names = listOf("youtube", "vpn", "nodes")
        assertEquals("vpn", MainProxyGroupPolicy.resolveName(names))
    }

    @Test
    fun fallsBackToFirst_whenNoPreferredAndAllCandidates() {
        val names = listOf("AppGroup", "Other")
        assertEquals("AppGroup", MainProxyGroupPolicy.resolveName(names))
    }

    @Test
    fun firstSelector_whenPreferredIsNotCandidate() {
        val names = listOf("VPN", "REAL", "OTHER")
        // Prefer VPN by name, but only REAL is Selector-like.
        val resolved = MainProxyGroupPolicy.resolveName(names) { it == "REAL" }
        assertEquals("REAL", resolved)
    }

    @Test
    fun preferredSelector_winsOverLaterSelector() {
        val names = listOf("YOUTUBE", "VPN", "PROXY")
        val resolved = MainProxyGroupPolicy.resolveName(names) { it == "VPN" || it == "PROXY" }
        assertEquals("VPN", resolved)
    }

    @Test
    fun lastResortFirst_whenNoCandidateMatches() {
        val names = listOf("A", "B")
        assertEquals("A", MainProxyGroupPolicy.resolveName(names) { false })
    }
}
