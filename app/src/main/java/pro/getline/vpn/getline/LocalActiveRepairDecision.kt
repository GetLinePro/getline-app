package pro.getline.vpn.getline

import kotlinx.coroutines.delay
import java.io.File

/**
 * Local Ready oracle: DAO inventory plus the on-disk profile directory.
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
        profileDirectory: (uuid: String) -> File,
        debounceMs: Long = 0L,
    ): LocalActiveRepair {
        val managed = managedUuid?.takeIf { it.isNotBlank() }
        val imported = importedUuids.filter { it.isNotBlank() }.toSet()
        val candidates = listOfNotNull(
            activeUuid?.takeIf { it in imported },
            managed?.takeIf { it in imported },
        ).distinct()
        val verdicts = candidates.associateWith { uuid ->
            inspectDebounced(profileDirectory(uuid), debounceMs)
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
            val verdict = verdicts[managed]
                ?: inspectDebounced(profileDirectory(managed), debounceMs)
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

    /**
     * Best-effort debounce, not a lock and not a bound.
     *
     * ProfileProcessor deletes the imported dir before copying the replacement.
     * A one-shot inspect from the app process can land in that window and
     * classify a healthy update as corrupt. A short delay reduces false
     * positives; it does not prevent them — copy can take longer than this.
     */
    private suspend fun inspectDebounced(
        profileDir: File,
        debounceMs: Long,
    ): ImportedProfileIntegrity.Verdict {
        val first = ImportedProfileIntegrity.inspect(profileDir)
        if (first == ImportedProfileIntegrity.Verdict.Intact || debounceMs <= 0L) {
            return first
        }
        delay(debounceMs)
        return ImportedProfileIntegrity.inspect(profileDir)
    }
}
