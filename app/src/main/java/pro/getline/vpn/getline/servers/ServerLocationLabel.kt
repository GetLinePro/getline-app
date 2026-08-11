package pro.getline.vpn.getline.servers

/**
 * Text for the Home location row.
 *
 * A nested group names the mode, not the node ("⚡ Авто"), so both are shown:
 * "⚡ Авто → 🇩🇪 Германия". A plain pick stays a plain name.
 *
 * The leaf deliberately drops its variant suffix ("· vless"): the row is one
 * ellipsized line, and the qualified form pushes the very part this exists to
 * reveal past the end. The Servers list still spells the leaf in full.
 */
object ServerLocationLabel {
    private const val ARROW = " → "

    /**
     * @return null when there is nothing to name — the caller shows the
     *   "unknown location" string, exactly as before.
     */
    fun of(selectedName: String?, resolvedName: String? = null): String? {
        val selected = selectedName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val label = ServerNameParser.parse(selected).displayQualifiedLabel

        // Unresolved chains arrive here as null; a group resolving to its own
        // name would render "X → X".
        val leaf = resolvedName?.trim()?.takeIf { it.isNotEmpty() && it != selected }
            ?: return label

        return label + ARROW + ServerNameParser.parse(leaf).displayLabel
    }
}
