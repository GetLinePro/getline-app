package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLocationLabelTest {
    @Test
    fun plainPickKeepsItsQualifiedName() {
        assertEquals(
            "🇩🇪 Германия · vless",
            ServerLocationLabel.of("🇩🇪 Германия | vless"),
        )
    }

    @Test
    fun selectedGroupNamesBothModeAndNode() {
        assertEquals(
            "⚡ Авто → 🇩🇪 Германия",
            ServerLocationLabel.of("⚡ Авто", "🇩🇪 Германия | vless"),
        )
    }

    /** One ellipsized line: the leaf gives up its variant so it stays visible. */
    @Test
    fun leafDropsVariantSuffix() {
        val label = ServerLocationLabel.of("⚡ Авто", "🇳🇱 Нидерланды | Обход hy2")

        assertEquals("⚡ Авто → 🇳🇱 Нидерланды", label)
    }

    /** Unresolved chain (cycle, missing group, too deep) — honest "Авто", not an error. */
    @Test
    fun unresolvedGroupStaysTheGroup() {
        assertEquals("⚡ Авто", ServerLocationLabel.of("⚡ Авто", null))
    }

    @Test
    fun blankResolutionIsIgnored() {
        assertEquals("⚡ Авто", ServerLocationLabel.of("⚡ Авто", "   "))
    }

    @Test
    fun groupResolvingToItselfIsNotRepeated() {
        assertEquals("⚡ Авто", ServerLocationLabel.of("⚡ Авто", "⚡ Авто"))
    }

    @Test
    fun unparsedNameSurvivesVerbatim() {
        assertEquals("node-7", ServerLocationLabel.of("node-7"))
    }

    @Test
    fun nothingSelectedYieldsNoLabel() {
        assertNull(ServerLocationLabel.of(null))
        assertNull(ServerLocationLabel.of(""))
        assertNull(ServerLocationLabel.of("  ", "🇩🇪 Германия"))
    }
}
