package pro.getline.vpn.getline.servers

/**
 * One selectable variant inside a country group.
 * [rawName] is the Mihomo identity passed back to patchSelector.
 *
 * [activeViaGroup] marks the leaf a nested group (e.g. "⚡ Авто") currently
 * routes through. It is not the selector's choice, so it is shown differently
 * from [selected] — without it the running node is invisible and the user can
 * re-pick what is already active without knowing.
 */
data class ServerVariantRow(
    val rawName: String,
    val label: String,
    val protocol: String?,
    val delayMs: Int?,
    val selected: Boolean,
    val activeViaGroup: Boolean = false,
)

/**
 * A country row in the Servers list, or a single ungrouped entry.
 *
 * Tapping the row selects [primaryRawName]. The chevron reveals [variants];
 * a group with one variant has nothing to expand.
 */
data class ServerGroupRow(
    val key: String,
    val label: String,
    val countryCode: String?,
    val section: ServerSection,
    val primaryRawName: String,
    val primaryVariantLabel: String?,
    val primaryProtocol: String?,
    val primaryDelayMs: Int?,
    /** Leaf a nested group resolves to, already formatted for display. */
    val resolvedLabel: String?,
    val variants: List<ServerVariantRow>,
    val selected: Boolean,
) {
    val expandable: Boolean
        get() = variants.size > 1
}

/**
 * Groups raw Mihomo proxy names by country for the Servers destination.
 *
 * The main group is a Selector: it does not pick a healthy node on its own, so
 * choosing which variant a country row activates is this policy's job.
 */
object ServerGroupingPolicy {
    /**
     * Mihomo reports 0 for "never tested" and an out-of-range value for timeout.
     * Neither may win a "fastest" comparison, so only this range ranks.
     */
    private val RANKABLE_DELAY = 1..Short.MAX_VALUE.toInt()

    /**
     * @param preferredByGroup last variant the user picked per group key. Without
     *   it, moving to another country re-derives the primary by delay and the
     *   earlier choice visibly resets.
     */
    fun group(
        servers: List<VpnServerItem>,
        selectedRawName: String?,
        preferredByGroup: Map<String, String> = emptyMap(),
    ): List<ServerGroupRow> {
        if (servers.isEmpty()) return emptyList()

        val parsed = servers.map { it to ServerNameParser.parse(it.name) }

        // The leaf a selected nested group routes through, if any.
        val activeLeaf = servers
            .firstOrNull { it.name == selectedRawName && it.isGroup }
            ?.resolvedName

        // Preserve config order: a group takes the position of its first member.
        val buckets = LinkedHashMap<String, MutableList<Pair<VpnServerItem, ParsedServerName>>>()
        for (entry in parsed) {
            buckets.getOrPut(keyOf(entry.second, entry.first)) { mutableListOf() }.add(entry)
        }

        // Stable sort: sections come out in declaration order, config order holds
        // inside each one.
        return buckets.entries
            .sortedBy { (_, entries) -> sectionOf(entries).ordinal }
            .map { (key, entries) ->
                buildGroup(key, entries, selectedRawName, preferredByGroup[key], activeLeaf)
            }
    }

    /** Group key for a parsed name. Ungrouped entries never merge. */
    fun keyOf(parsed: ParsedServerName, item: VpnServerItem): String =
        keyOf(ServerSectionPolicy.sectionOf(item.name), parsed.countryCode ?: "raw:${item.name}")

    /** Group key for a raw name — used when recording the user's choice. */
    fun keyOfRawName(rawName: String): String {
        val parsed = ServerNameParser.parse(rawName)
        return keyOf(
            ServerSectionPolicy.sectionOf(rawName),
            parsed.countryCode ?: "raw:$rawName",
        )
    }

    /**
     * The section is part of the key so nodes that share a flag with another
     * section cannot merge: LTE nodes carry 🇫🇲, and a plain 🇫🇲 node would
     * otherwise land in the same bucket. Keys live only in memory (holder
     * preferences, expanded rows), so their shape is free to change.
     */
    private fun keyOf(section: ServerSection, base: String): String =
        "${section.name}:$base"

    /** Section of a bucket: its members all carry the same marker. */
    private fun sectionOf(entries: List<Pair<VpnServerItem, ParsedServerName>>): ServerSection =
        ServerSectionPolicy.sectionOf(entries.first().first.name)

    private fun buildGroup(
        key: String,
        entries: List<Pair<VpnServerItem, ParsedServerName>>,
        selectedRawName: String?,
        preferredRawName: String?,
        activeLeaf: String?,
    ): ServerGroupRow {
        val first = entries.first().second
        val labels = variantLabels(entries)

        val variants = entries.mapIndexed { index, (item, _) ->
            ServerVariantRow(
                rawName = item.name,
                label = labels[index],
                protocol = item.protocol,
                delayMs = item.delayMs?.takeIf { it in RANKABLE_DELAY },
                selected = item.name == selectedRawName,
                activeViaGroup = activeLeaf != null && item.name == activeLeaf,
            )
        }

        val primary = choosePrimary(variants, preferredRawName)
        val resolved = entries
            .firstOrNull { it.first.name == primary.rawName }
            ?.first
            ?.resolvedName

        return ServerGroupRow(
            key = key,
            label = first.displayLabel,
            countryCode = first.countryCode,
            section = sectionOf(entries),
            primaryRawName = primary.rawName,
            // Always shown: a lone variant can still carry meaning ("YT no ads").
            primaryVariantLabel = primary.label.takeIf { it != first.groupLabel },
            primaryProtocol = primary.protocol,
            primaryDelayMs = primary.delayMs,
            resolvedLabel = resolved?.let { ServerNameParser.parse(it).displayQualifiedLabel },
            variants = variants,
            selected = variants.any { it.selected },
        )
    }

    /**
     * Variant labels within one group, disambiguated.
     *
     * Two nodes can share a suffix while differing before the separator
     * ("Нидерланды | vless" and "Амстердам | vless"), which rendered as two
     * identical rows. Colliding labels fall back to the qualified name.
     */
    private fun variantLabels(
        entries: List<Pair<VpnServerItem, ParsedServerName>>,
    ): List<String> {
        val short = entries.map { (_, name) -> name.variantLabel ?: name.groupLabel }
        val duplicated = short.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicated.isEmpty()) return short

        return entries.mapIndexed { index, (_, name) ->
            if (short[index] in duplicated) name.qualifiedLabel else short[index]
        }
    }

    /**
     * Which variant a country row activates:
     * 1. the one already active — tapping the current country must not switch nodes;
     * 2. the user's last pick in this group, so leaving and returning is stable;
     * 3. otherwise the lowest measured delay;
     * 4. otherwise the first in config order.
     */
    private fun choosePrimary(
        variants: List<ServerVariantRow>,
        preferredRawName: String?,
    ): ServerVariantRow {
        variants.firstOrNull { it.selected }?.let { return it }

        if (preferredRawName != null) {
            variants.firstOrNull { it.rawName == preferredRawName }?.let { return it }
        }

        return variants
            .filter { it.delayMs != null }
            .minByOrNull { it.delayMs!! }
            ?: variants.first()
    }
}
