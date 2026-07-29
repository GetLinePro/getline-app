package pro.getline.vpn.cmfa.servers

import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveLeafNameTest {
    private fun node(name: String, isGroup: Boolean = false) = Proxy(
        name = name,
        title = name,
        subtitle = "",
        type = if (isGroup) "Selector" else "Vless",
        delay = 0,
        isGroup = isGroup,
    )

    private fun group(now: String, vararg children: Proxy) =
        ProxyGroup(type = "Selector", proxies = children.toList(), now = now)

    @Test
    fun leafChildIsReturnedDirectly() {
        val graph = mapOf(
            "Авто" to group("🇵🇱 Польша | grpc", node("🇵🇱 Польша | grpc")),
        )

        val leaf = CmfaVpnServerSelectionRepository.resolveLeafName("Авто") { graph[it] }

        assertEquals("🇵🇱 Польша | grpc", leaf)
    }

    @Test
    fun descendsThroughAnIntermediateGroup() {
        // Youtube -> VPN -> node: one hop would have reported "VPN".
        val graph = mapOf(
            "Youtube" to group("VPN", node("VPN", isGroup = true)),
            "VPN" to group("🇩🇪 Германия | vless", node("🇩🇪 Германия | vless")),
        )

        val leaf = CmfaVpnServerSelectionRepository.resolveLeafName("Youtube") { graph[it] }

        assertEquals("🇩🇪 Германия | vless", leaf)
    }

    @Test
    fun descendsThroughSeveralGroups() {
        val graph = mapOf(
            "A" to group("B", node("B", isGroup = true)),
            "B" to group("C", node("C", isGroup = true)),
            "C" to group("leaf", node("leaf")),
        )

        assertEquals("leaf", CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }

    @Test
    fun cycleYieldsNothingRatherThanLooping() {
        val graph = mapOf(
            "A" to group("B", node("B", isGroup = true)),
            "B" to group("A", node("A", isGroup = true)),
        )

        assertNull(CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }

    @Test
    fun selfReferenceYieldsNothing() {
        val graph = mapOf("A" to group("A", node("A", isGroup = true)))

        assertNull(CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }

    @Test
    fun chainDeeperThanTheCapYieldsNothing() {
        // Better to show nothing than to name a node that is not in use.
        val graph = (0..20).associate { i ->
            "g$i" to group("g${i + 1}", node("g${i + 1}", isGroup = true))
        }

        assertNull(CmfaVpnServerSelectionRepository.resolveLeafName("g0") { graph[it] })
    }

    @Test
    fun chainWithinTheCapResolves() {
        val graph = buildMap {
            for (i in 0 until 6) {
                put("g$i", group("g${i + 1}", node("g${i + 1}", isGroup = true)))
            }
            put("g6", group("leaf", node("leaf")))
        }

        assertEquals("leaf", CmfaVpnServerSelectionRepository.resolveLeafName("g0") { graph[it] })
    }

    @Test
    fun missingGroupYieldsNothing() {
        assertNull(CmfaVpnServerSelectionRepository.resolveLeafName("A") { null })
    }

    @Test
    fun blankSelectionYieldsNothing() {
        val graph = mapOf("A" to group("", node("x")))

        assertNull(CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }

    @Test
    fun unknownChildIsReportedAsIs() {
        // The group names a proxy it does not list; its own word is the best we have.
        val graph = mapOf("A" to group("mystery"))

        assertEquals("mystery", CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }

    @Test
    fun stopsAtFirstLeafEvenWhenDeeperGroupsExist() {
        val graph = mapOf(
            "A" to group("leaf", node("leaf"), node("B", isGroup = true)),
            "B" to group("other", node("other")),
        )

        assertEquals("leaf", CmfaVpnServerSelectionRepository.resolveLeafName("A") { graph[it] })
    }
}
