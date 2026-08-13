package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])

class ServerCatalogProjectionTest {
    @Test
    fun parse_readsGroupsAndHidden() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        assertEquals(1, catalog.version)
        assertEquals("rule", catalog.mode)
        assertEquals(3, catalog.groups.size)
        assertEquals("VPN", catalog.groups[1].name)
        assertEquals("Selector", catalog.groups[1].type)
        assertTrue(catalog.groups[2].hidden)
        assertTrue(catalog.groups[1].proxies[0].group)
        assertFalse(catalog.groups[1].proxies[1].group)
    }

    @Test
    fun toLoadResult_usesMainSelectorAndOverride() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
            selections = mapOf("VPN" to "leaf-a"),
        ) as VpnServerLoadResult.Success

        assertEquals("VPN", result.groupName)
        assertEquals("leaf-a", result.selectedName)
        assertTrue(result.selectable)
        assertEquals(listOf("AUTO", "leaf-a", "leaf-b"), result.servers.map { it.name })
        assertEquals("leaf-a", result.servers.first { it.name == "AUTO" }.resolvedName)
        assertNull(result.servers.first { it.name == "leaf-a" }.delayMs)
    }

    @Test
    fun toLoadResult_skipsHiddenAndNonSelectors() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
        ) as VpnServerLoadResult.Success

        assertEquals("VPN", result.groupName)
        assertEquals("AUTO", result.selectedName)
    }

    @Test
    fun toLoadResult_ignoresOverrideOutsideGroup() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
            selections = mapOf("VPN" to "missing"),
        ) as VpnServerLoadResult.Success

        assertEquals("AUTO", result.selectedName)
    }

    @Test
    fun toMainSelection_resolvesNestedGroup() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        val selection = ServerCatalogProjection.toMainSelection(
            catalog = catalog,
            excludeNotSelectable = true,
        )

        assertEquals("AUTO", selection?.selectedName)
        assertEquals("leaf-a", selection?.resolvedName)
    }

    @Test
    fun resolveLeaf_usesNestedSelectorChoice() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
            selections = mapOf(
                "VPN" to "AUTO",
                "AUTO" to "leaf-b",
            ),
        ) as VpnServerLoadResult.Success

        assertEquals("AUTO", result.selectedName)
        assertEquals("leaf-b", result.servers.first { it.name == "AUTO" }.resolvedName)
        assertEquals(
            "leaf-b",
            ServerCatalogProjection.toMainSelection(
                catalog = catalog,
                excludeNotSelectable = true,
                selections = mapOf("VPN" to "AUTO", "AUTO" to "leaf-b"),
            )?.resolvedName,
        )
    }

    @Test
    fun toLoadResult_directMode_isEmpty() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON.replace("\"rule\"", "\"direct\""))
        assertEquals(
            VpnServerLoadResult.Empty,
            ServerCatalogProjection.toLoadResult(catalog, excludeNotSelectable = true),
        )
    }

    @Test
    fun toLoadResult_globalMode_usesGlobalGroup() {
        val catalog = ServerCatalog.parse(GLOBAL_JSON)
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
            selections = mapOf("GLOBAL" to "leaf-b"),
        ) as VpnServerLoadResult.Success

        assertEquals("GLOBAL", result.groupName)
        assertEquals("leaf-b", result.selectedName)
        assertEquals(listOf("VPN", "AUTO", "leaf-a", "leaf-b"), result.servers.map { it.name })
    }

    @Test
    fun toLoadResult_sortByTitle_matchesLiveTitleOrder() {
        val catalog = ServerCatalog.parse(
            """
            {
              "version": 1,
              "mode": "rule",
              "groups": [
                {
                  "name": "VPN",
                  "type": "Selector",
                  "now": "zeta",
                  "hidden": false,
                  "proxies": [
                    {"name": "zeta", "type": "Socks5", "group": false},
                    {"name": "Beta", "type": "Socks5", "group": false},
                    {"name": "alpha", "type": "Socks5", "group": false}
                  ]
                }
              ]
            }
            """,
        )
        val fileOrder = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
        ) as VpnServerLoadResult.Success
        val titleOrder = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
            sortByTitle = true,
        ) as VpnServerLoadResult.Success
        assertEquals(listOf("zeta", "Beta", "alpha"), fileOrder.servers.map { it.name })
        // Same comparator as live Title: strings.Compare on the name, case-sensitive.
        assertEquals(listOf("Beta", "alpha", "zeta"), titleOrder.servers.map { it.name })
    }

    @Test
    fun toLoadResult_missingMode_treatedAsRule() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON.replace("\"mode\": \"rule\",", ""))
        val result = ServerCatalogProjection.toLoadResult(
            catalog = catalog,
            excludeNotSelectable = true,
        ) as VpnServerLoadResult.Success
        assertEquals("VPN", result.groupName)
    }

    @Test
    fun contains_checksMembership() {
        val catalog = ServerCatalog.parse(SAMPLE_JSON)
        assertTrue(ServerCatalogProjection.contains(catalog, "VPN", "leaf-b"))
        assertFalse(ServerCatalogProjection.contains(catalog, "VPN", "hidden-tech"))
        assertFalse(ServerCatalogProjection.contains(catalog, "missing", "leaf-a"))
    }

    @Test
    fun read_missingFile_returnsNull() {
        val dir = kotlin.io.path.createTempDirectory("catalog").toFile()
        try {
            assertNull(ServerCatalog.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun read_validFile() {
        val dir = kotlin.io.path.createTempDirectory("catalog").toFile()
        try {
            ServerCatalog.fileIn(dir).writeText(SAMPLE_JSON)
            val catalog = ServerCatalog.read(dir)
            assertEquals("VPN", catalog?.groups?.get(1)?.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun read_unknownVersion_returnsNull() {
        val dir = kotlin.io.path.createTempDirectory("catalog").toFile()
        try {
            // Written by a newer core: fields this projection does not understand.
            ServerCatalog.fileIn(dir)
                .writeText(SAMPLE_JSON.replace("\"version\": 1", "\"version\": 2"))
            assertNull(ServerCatalog.read(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        const val SAMPLE_JSON = """
        {
          "version": 1,
          "mode": "rule",
          "groups": [
            {
              "name": "AUTO",
              "type": "URLTest",
              "now": "leaf-a",
              "hidden": false,
              "proxies": [
                {"name": "leaf-a", "type": "Socks5", "group": false},
                {"name": "leaf-b", "type": "Socks5", "group": false}
              ]
            },
            {
              "name": "VPN",
              "type": "Selector",
              "now": "AUTO",
              "hidden": false,
              "proxies": [
                {"name": "AUTO", "type": "URLTest", "group": true},
                {"name": "leaf-a", "type": "Socks5", "group": false},
                {"name": "leaf-b", "type": "Socks5", "group": false}
              ]
            },
            {
              "name": "hidden-tech",
              "type": "Selector",
              "now": "leaf-a",
              "hidden": true,
              "proxies": [
                {"name": "leaf-a", "type": "Socks5", "group": false}
              ]
            }
          ]
        }
        """

        const val GLOBAL_JSON = """
        {
          "version": 1,
          "mode": "global",
          "groups": [
            {
              "name": "GLOBAL",
              "type": "Selector",
              "now": "VPN",
              "hidden": false,
              "proxies": [
                {"name": "VPN", "type": "Selector", "group": true},
                {"name": "AUTO", "type": "URLTest", "group": true},
                {"name": "leaf-a", "type": "Socks5", "group": false},
                {"name": "leaf-b", "type": "Socks5", "group": false}
              ]
            },
            {
              "name": "AUTO",
              "type": "URLTest",
              "now": "leaf-a",
              "hidden": false,
              "proxies": [
                {"name": "leaf-a", "type": "Socks5", "group": false},
                {"name": "leaf-b", "type": "Socks5", "group": false}
              ]
            },
            {
              "name": "VPN",
              "type": "Selector",
              "now": "AUTO",
              "hidden": false,
              "proxies": [
                {"name": "AUTO", "type": "URLTest", "group": true},
                {"name": "leaf-a", "type": "Socks5", "group": false}
              ]
            }
          ]
        }
        """
    }
}
