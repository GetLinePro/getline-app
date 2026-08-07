package pro.getline.vpn.getline.servers

/**
 * Where a node belongs on the Servers list. Declaration order is render order.
 *
 * [Main] is the country pool. [Lte] and [Youtube] are special-purpose nodes that
 * the core sorts into the middle of the alphabet (LTE carries a 🇫🇲 flag, the
 * YouTube node a 🇷🇺 one), which is why they read as noise without a section.
 */
enum class ServerSection {
    Main,
    Lte,
    Youtube,
}

/**
 * Sorts raw Mihomo node names into list sections by the marker emoji in the name.
 *
 * The name is the only signal available: the profile expresses the same grouping
 * through `filter: "🥷"` / `exclude-filter: "🥷|📺"`, and the hidden groups that
 * hold these nodes are filtered out of the bridge's group list. Reading the
 * markers keeps one contract instead of hardcoding group names in Kotlin.
 *
 * A renamed node silently lands in [ServerSection.Main] — visible, never dropped.
 */
object ServerSectionPolicy {
    private const val LTE_MARKER = "🥷"
    private const val YOUTUBE_MARKER = "📺"

    fun sectionOf(rawName: String): ServerSection = when {
        rawName.contains(LTE_MARKER) -> ServerSection.Lte
        rawName.contains(YOUTUBE_MARKER) -> ServerSection.Youtube
        else -> ServerSection.Main
    }

    /**
     * Which rows carry a heading, aligned with [sections] one-to-one.
     *
     * A heading marks the first row of a run. A list with one section gets none
     * at all: naming the only section there is costs a row and says nothing.
     *
     * @param sections section of each row, in render order.
     */
    fun headings(sections: List<ServerSection>): List<ServerSection?> {
        if (sections.distinct().size < 2) return sections.map { null }

        var previous: ServerSection? = null
        return sections.map { section ->
            section.takeIf { it != previous }.also { previous = section }
        }
    }
}
