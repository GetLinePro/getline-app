package pro.getline.vpn.getline

/**
 * Local Ready oracle: service-owned inventory plus storage health.
 *
 * [importedUuids] are ImportedDao rows. A row without a usable directory is
 * [LocalActiveRepair.ManagedCorrupt], not Ready and not a local-activate
 * candidate. ActiveProfilePolicy still picks the UUID; this only withholds
 * broken directories from it.
 */
internal object LocalActiveRepairDecision {
    suspend fun decide(
        managedUuid: String?,
        importedUuids: Collection<String>,
        activeUuid: String?,
        profileHealth: Map<String, ImportedProfileIntegrity.Verdict>,
    ): LocalActiveRepair {
        val managed = managedUuid?.takeIf { it.isNotBlank() }
        val imported = importedUuids.filter { it.isNotBlank() }.toSet()
        val candidates = listOfNotNull(
            activeUuid?.takeIf { it in imported },
            managed?.takeIf { it in imported },
        ).distinct()
        // The service checks every candidate while holding the profile lock.
        // Missing data is treated as corrupt defensively, never as permission
        // to activate an unchecked profile.
        val verdicts = candidates.associateWith { uuid ->
            profileHealth[uuid] ?: ImportedProfileIntegrity.Verdict.MissingDirectory
        }
        val intact = verdicts.filterValues {
            it == ImportedProfileIntegrity.Verdict.Intact
        }.keys

        val target = ActiveProfilePolicy.resolveUuidToActivate(
            activeUuid = activeUuid?.takeIf { it in intact },
            importedUuids = intact,
            managedUuid = managed,
        )
        if (target != null) {
            return LocalActiveRepair.Ready(target)
        }

        val after = activeUuid?.takeIf { it in intact }
        if (after != null) {
            return LocalActiveRepair.Ready(after)
        }

        if (managed != null && managed in imported && managed !in intact) {
            val verdict = verdicts.getValue(managed)
            return LocalActiveRepair.ManagedCorrupt(
                managedUuid = managed,
                detail = verdict.logToken,
            )
        }

        return LocalActiveRepair.ManagedAbsent(
            managedUuid = managed,
            managedIsImported = managed != null && managed in imported,
        )
    }
}
