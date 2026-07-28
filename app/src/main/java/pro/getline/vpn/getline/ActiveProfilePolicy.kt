package pro.getline.vpn.getline

/**
 * Pure policy: which UUID to [setActive] for a **local-only** heal step.
 *
 * Prefer [VpnConfigurationRepairPolicy] for the full Retry ladder (local → remote).
 * This stays focused on: never activate an arbitrary imported profile.
 */
object ActiveProfilePolicy {
    /**
     * @param activeUuid current ServiceStore active profile, if any
     * @param importedUuids profiles that exist in ImportedDao
     * @param managedUuid persisted GetLine-managed profile UUID (account/URL import binding)
     * @return uuid to activate, or null if nothing to change / cannot decide safely
     */
    fun resolveUuidToActivate(
        activeUuid: String?,
        importedUuids: Collection<String>,
        managedUuid: String?,
    ): String? {
        val imported = importedUuids.filter { it.isNotBlank() }.toSet()
        if (imported.isEmpty()) return null

        // Valid active already selected (including Advanced override) — leave alone.
        if (activeUuid != null && activeUuid in imported) return null

        // Only re-select the GetLine-managed binding when still present on disk.
        return managedUuid
            ?.takeIf { it.isNotBlank() && it in imported }
    }
}
