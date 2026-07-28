package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerGroupingPolicyTest {
    private fun item(name: String, delay: Int? = null) =
        VpnServerItem(name = name, displayName = name, delayMs = delay)

    /** The list observed on a real device, in config order. */
    private fun realWorldServers() = listOf(
        item("⚡ Авто"),
        item("🇳🇱 Нидерланды | Обход"),
        item("🇳🇱 Нидерланды | Обход hy2"),
        item("🇳🇱 Нидерланды | hy2"),
        item("🇳🇱 Нидерланды | vless"),
        item("🇳🇱 Нидерланды | xhttp"),
        item("🇩🇪 Германия | Обход"),
        item("🇩🇪 Германия | Обход vless"),
        item("🇩🇪 Германия | hy2"),
        item("🇩🇪 Германия | xhttp"),
    )

    @Test
    fun collapsesNineRowsIntoThree() {
        val groups = ServerGroupingPolicy.group(realWorldServers(), selectedRawName = null)

        assertEquals(
            listOf("⚡ Авто", "🇳🇱 Нидерланды", "🇩🇪 Германия"),
            groups.map { it.label },
        )
    }

    @Test
    fun preservesConfigOrder_autoStaysFirst() {
        val groups = ServerGroupingPolicy.group(realWorldServers(), selectedRawName = null)

        assertEquals("⚡ Авто", groups.first().label)
        assertNull(groups.first().countryCode)
    }

    @Test
    fun autoIsNotExpandable() {
        val groups = ServerGroupingPolicy.group(realWorldServers(), selectedRawName = null)

        assertFalse(groups.first().expandable)
        assertEquals(1, groups.first().variants.size)
    }

    @Test
    fun countryGroupKeepsAllVariants() {
        val groups = ServerGroupingPolicy.group(realWorldServers(), selectedRawName = null)
        val netherlands = groups.first { it.countryCode == "NL" }

        assertEquals(5, netherlands.variants.size)
        assertTrue(netherlands.expandable)
        assertEquals(
            listOf("Обход", "Обход hy2", "hy2", "vless", "xhttp"),
            netherlands.variants.map { it.label },
        )
    }

    @Test
    fun activeVariantWins_tappingCurrentCountryDoesNotSwitchNode() {
        val servers = listOf(
            item("🇩🇪 Германия | Обход", delay = 20),
            item("🇩🇪 Германия | xhttp", delay = 90),
        )

        val group = ServerGroupingPolicy
            .group(servers, selectedRawName = "🇩🇪 Германия | xhttp")
            .single()

        // Faster variant exists, but the active one must stay put.
        assertEquals("🇩🇪 Германия | xhttp", group.primaryRawName)
        assertTrue(group.selected)
    }

    @Test
    fun picksLowestDelay_whenNothingSelectedInGroup() {
        val servers = listOf(
            item("🇩🇪 Германия | Обход", delay = 120),
            item("🇩🇪 Германия | hy2", delay = 28),
            item("🇩🇪 Германия | xhttp", delay = 60),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("🇩🇪 Германия | hy2", group.primaryRawName)
        assertEquals(28, group.primaryDelayMs)
    }

    @Test
    fun zeroDelayNeverWins() {
        // 0 means "never tested" — treating it as fastest would pick a dead node.
        val servers = listOf(
            item("🇩🇪 Германия | Обход", delay = 0),
            item("🇩🇪 Германия | hy2", delay = 44),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("🇩🇪 Германия | hy2", group.primaryRawName)
    }

    @Test
    fun timeoutDelayNeverWins() {
        val servers = listOf(
            item("🇩🇪 Германия | Обход", delay = Int.MAX_VALUE),
            item("🇩🇪 Германия | hy2", delay = 300),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("🇩🇪 Германия | hy2", group.primaryRawName)
    }

    @Test
    fun fallsBackToConfigOrder_whenNoDelaysMeasured() {
        val servers = listOf(
            item("🇩🇪 Германия | Обход"),
            item("🇩🇪 Германия | hy2"),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("🇩🇪 Германия | Обход", group.primaryRawName)
        assertNull(group.primaryDelayMs)
    }

    @Test
    fun unmeasuredDelayIsNotReportedAsZero() {
        val servers = listOf(item("🇩🇪 Германия | hy2", delay = 0))

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertNull(group.variants.single().delayMs)
    }

    @Test
    fun ungroupedEntriesNeverMerge() {
        val servers = listOf(
            item("⚡ Авто"),
            item("Германия | hy2"),
            item("Ручной узел"),
        )

        val groups = ServerGroupingPolicy.group(servers, selectedRawName = null)

        assertEquals(3, groups.size)
        assertTrue(groups.all { it.countryCode == null })
    }

    @Test
    fun noServerIsEverDropped() {
        val servers = realWorldServers()

        val groups = ServerGroupingPolicy.group(servers, selectedRawName = null)

        val allRawNames = groups.flatMap { g -> g.variants.map { it.rawName } }
        assertEquals(servers.map { it.name }.toSet(), allRawNames.toSet())
        assertEquals(servers.size, allRawNames.size)
    }

    @Test
    fun singleVariantGroupStillShowsItsLabel() {
        // "🇷🇺 РФ | YT no ads" rendered as bare "РФ" and the purpose was lost.
        val servers = listOf(item("🇷🇺 РФ | YT no ads"))

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("YT no ads", group.primaryVariantLabel)
    }

    @Test
    fun groupLabelKeepsItsFlag() {
        val group = ServerGroupingPolicy
            .group(listOf(item("🇩🇪 Германия | xhttp")), selectedRawName = null)
            .single()

        assertEquals("🇩🇪 Германия", group.label)
    }

    @Test
    fun collidingVariantLabelsAreDisambiguated() {
        // Both end in "vless" but differ before the separator — two identical rows.
        val servers = listOf(
            item("🇳🇱 Нидерланды | vless", delay = 79),
            item("🇳🇱 Амстердам | vless", delay = 73),
        )

        val labels = ServerGroupingPolicy
            .group(servers, selectedRawName = null)
            .single()
            .variants
            .map { it.label }

        assertEquals(listOf("Нидерланды · vless", "Амстердам · vless"), labels)
        assertEquals(labels.toSet().size, labels.size)
    }

    @Test
    fun distinctVariantLabelsStayShort() {
        val servers = listOf(
            item("🇩🇪 Германия | hy2"),
            item("🇩🇪 Германия | vless"),
        )

        val labels = ServerGroupingPolicy
            .group(servers, selectedRawName = null)
            .single()
            .variants
            .map { it.label }

        assertEquals(listOf("hy2", "vless"), labels)
    }

    @Test
    fun preferenceSurvivesSwitchingToAnotherCountry() {
        // The reported bug: pick a slower NL variant, move to DE, and NL reset
        // to its fastest node.
        val servers = listOf(
            item("🇳🇱 Нидерланды | hy2", delay = 40),
            item("🇳🇱 Нидерланды | xhttp", delay = 99),
            item("🇩🇪 Германия | vless", delay = 60),
        )

        val groups = ServerGroupingPolicy.group(
            servers = servers,
            selectedRawName = "🇩🇪 Германия | vless",
            preferredByGroup = mapOf("NL" to "🇳🇱 Нидерланды | xhttp"),
        )

        val netherlands = groups.first { it.countryCode == "NL" }
        assertEquals("🇳🇱 Нидерланды | xhttp", netherlands.primaryRawName)
    }

    @Test
    fun activeSelectionBeatsPreference() {
        val servers = listOf(
            item("🇩🇪 Германия | hy2", delay = 20),
            item("🇩🇪 Германия | vless", delay = 60),
        )

        val group = ServerGroupingPolicy.group(
            servers = servers,
            selectedRawName = "🇩🇪 Германия | hy2",
            preferredByGroup = mapOf("DE" to "🇩🇪 Германия | vless"),
        ).single()

        // The core is the source of truth; a stale preference must not win.
        assertEquals("🇩🇪 Германия | hy2", group.primaryRawName)
    }

    @Test
    fun stalePreferenceFallsBackToDelay() {
        val servers = listOf(
            item("🇩🇪 Германия | hy2", delay = 20),
            item("🇩🇪 Германия | vless", delay = 60),
        )

        val group = ServerGroupingPolicy.group(
            servers = servers,
            selectedRawName = null,
            preferredByGroup = mapOf("DE" to "🇩🇪 Германия | gone"),
        ).single()

        assertEquals("🇩🇪 Германия | hy2", group.primaryRawName)
    }

    @Test
    fun nestedGroupExposesTheNodeItRoutesThrough() {
        // "⚡ Авто" was selected and nothing in any sublist was marked, so the
        // running node was invisible.
        val servers = listOf(
            VpnServerItem(
                name = "⚡ Авто",
                displayName = "⚡ Авто",
                delayMs = 20,
                isGroup = true,
                resolvedName = "🇵🇱 Польша | grpc",
            ),
            item("🇵🇱 Польша | grpc", delay = 33),
            item("🇵🇱 Польша | xhttp", delay = 74),
        )

        val groups = ServerGroupingPolicy.group(servers, selectedRawName = "⚡ Авто")

        val auto = groups.first { it.key.startsWith("raw:") }
        assertEquals("🇵🇱 Польша · grpc", auto.resolvedLabel)

        val poland = groups.first { it.countryCode == "PL" }
        val active = poland.variants.filter { it.activeViaGroup }
        assertEquals(listOf("🇵🇱 Польша | grpc"), active.map { it.rawName })
        // It is running, but the selector did not choose it.
        assertFalse(active.single().selected)
    }

    @Test
    fun activeViaGroupIsNotMarkedWhenAutoIsNotSelected() {
        val servers = listOf(
            VpnServerItem(
                name = "⚡ Авто",
                displayName = "⚡ Авто",
                isGroup = true,
                resolvedName = "🇵🇱 Польша | grpc",
            ),
            item("🇵🇱 Польша | grpc", delay = 33),
        )

        val groups = ServerGroupingPolicy.group(
            servers,
            selectedRawName = "🇵🇱 Польша | grpc",
        )

        val poland = groups.first { it.countryCode == "PL" }
        assertFalse(poland.variants.single().activeViaGroup)
        assertTrue(poland.variants.single().selected)
    }

    @Test
    fun protocolAndNameSuffixAreCarriedSeparately() {
        // They name different layers — the core's adapter type and the tunnel in
        // the name — so both must survive rather than one overriding the other.
        val servers = listOf(
            VpnServerItem(
                name = "🇪🇪 Эстония | xhttp",
                displayName = "🇪🇪 Эстония | xhttp",
                protocol = "HY2",
            ),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("HY2", group.primaryProtocol)
        assertEquals("xhttp", group.primaryVariantLabel)
    }


    @Test
    fun multiVariantGroupShowsPrimaryAsSubtitle() {
        val servers = listOf(
            item("🇩🇪 Германия | hy2", delay = 30),
            item("🇩🇪 Германия | xhttp", delay = 90),
        )

        val group = ServerGroupingPolicy.group(servers, selectedRawName = null).single()

        assertEquals("hy2", group.primaryVariantLabel)
    }

    @Test
    fun onlyGroupContainingSelectionIsMarked() {
        val groups = ServerGroupingPolicy.group(
            realWorldServers(),
            selectedRawName = "🇩🇪 Германия | xhttp",
        )

        assertEquals("🇩🇪 Германия", groups.single { it.selected }.label)
    }

    @Test
    fun emptyInputYieldsNoGroups() {
        assertTrue(ServerGroupingPolicy.group(emptyList(), selectedRawName = null).isEmpty())
    }
}
