package pro.getline.vpn.getline.servers

/**
 * Product policy for which Mihomo proxy group is the "main" VPN selector.
 *
 * Not "first name in the list" as a silent contract. Order of preference:
 * 1. Known GetLine/product group names (exact, then case-insensitive)
 * 2. First group that passes [isMainCandidate] (typically type == Selector)
 * 3. [groupNames].first() as last-resort fallback for unknown configs
 *
 * Both Home location and Servers list must use this (via
 * [VpnServerSelectionRepository]) so UI and patchSelector stay aligned.
 *
 * Long-term: profile generator should emit a stable main-group id; until then
 * this is the single place to tighten matching.
 */
object MainProxyGroupPolicy {
    /**
     * Stable product names observed / expected for the primary selector.
     * Preference order matters; list order in Clash config does not.
     */
    val PREFERRED_PRODUCT_NAMES: List<String> = listOf(
        "VPN",
        "PROXY",
        "Proxy",
        "SELECT",
        "Select",
        "GLOBAL",
        "Global",
    )

    /**
     * @param groupNames ordered names from queryProxyGroupNames
     * @param isMainCandidate optional type/filter probe (e.g. Selector). Default accepts all.
     * @return main group name, or null if [groupNames] is empty
     */
    fun resolveName(
        groupNames: List<String>,
        isMainCandidate: (String) -> Boolean = { true },
    ): String? {
        if (groupNames.isEmpty()) return null

        for (preferred in preferredHits(groupNames)) {
            if (isMainCandidate(preferred)) return preferred
        }
        for (name in groupNames) {
            if (isMainCandidate(name)) return name
        }
        return groupNames.first()
    }

    private fun preferredHits(groupNames: List<String>): Sequence<String> = sequence {
        val seen = HashSet<String>()
        for (preferred in PREFERRED_PRODUCT_NAMES) {
            groupNames.firstOrNull { it == preferred }?.let { hit ->
                if (seen.add(hit)) yield(hit)
            }
        }
        for (preferred in PREFERRED_PRODUCT_NAMES) {
            groupNames.firstOrNull { it.equals(preferred, ignoreCase = true) }?.let { hit ->
                if (seen.add(hit)) yield(hit)
            }
        }
    }
}
