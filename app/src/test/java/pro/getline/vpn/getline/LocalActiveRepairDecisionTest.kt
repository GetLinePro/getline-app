package pro.getline.vpn.getline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalActiveRepairDecisionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun daoExists_missingDirectory_isCorrupt() {
        val decided = decide(
            managed = "managed",
            imported = listOf("managed"),
            active = "managed",
        )

        val corrupt = decided as LocalActiveRepair.ManagedCorrupt
        assertEquals("managed", corrupt.managedUuid)
        assertEquals("missing_dir", corrupt.detail)
    }

    @Test
    fun daoExists_emptyConfig_isCorrupt() {
        writeProfile("managed", config = "")

        val decided = decide(
            managed = "managed",
            imported = listOf("managed"),
            active = null,
        )

        val corrupt = decided as LocalActiveRepair.ManagedCorrupt
        assertEquals("empty_config", corrupt.detail)
    }

    @Test
    fun daoExists_partialDirectory_isCorrupt() {
        tmp.newFolder("managed").resolve("providers").mkdir()

        val decided = decide(
            managed = "managed",
            imported = listOf("managed"),
            active = null,
        )

        val corrupt = decided as LocalActiveRepair.ManagedCorrupt
        assertEquals("missing_config", corrupt.detail)
    }

    @Test
    fun daoExists_intactConfig_isReady() {
        writeProfile("managed", config = "proxies: []\n")

        val decided = decide(
            managed = "managed",
            imported = listOf("managed"),
            active = null,
        )

        assertEquals(LocalActiveRepair.Ready("managed"), decided)
    }

    @Test
    fun daoMissing_isAbsent() {
        val decided = decide(
            managed = "managed",
            imported = emptyList(),
            active = null,
        )

        assertEquals(
            LocalActiveRepair.ManagedAbsent("managed", managedIsImported = false),
            decided,
        )
    }

    @Test
    fun intactActive_isReadyWithoutSwitching() {
        writeProfile("other", config = "proxies: []\n")
        writeProfile("managed", config = "proxies: []\n")

        val decided = decide(
            managed = "managed",
            imported = listOf("other", "managed"),
            active = "other",
        )

        assertEquals(LocalActiveRepair.Ready("other"), decided)
    }

    @Test
    fun corruptActive_restoresIntactManaged() {
        writeProfile("managed", config = "proxies: []\n")
        // "other" is in DAO but directory is gone.

        val decided = decide(
            managed = "managed",
            imported = listOf("other", "managed"),
            active = "other",
        )

        assertEquals(LocalActiveRepair.Ready("managed"), decided)
    }

    @Test
    fun inspectsOnlyActiveAndManaged() {
        writeProfile("other", config = "proxies: []\n")
        writeProfile("managed", config = "proxies: []\n")
        val seen = mutableListOf<String>()

        runBlocking {
            LocalActiveRepairDecision.decide(
                managedUuid = "managed",
                importedUuids = listOf("stale", "other", "managed"),
                activeUuid = "other",
                profileDirectory = { uuid ->
                    seen += uuid
                    tmp.root.resolve(uuid)
                },
            )
        }

        assertEquals(listOf("other", "managed"), seen)
    }

    @Test
    fun corruptManaged_doesNotReadyOtherIntactImport() {
        writeProfile("other", config = "proxies: []\n")

        val decided = decide(
            managed = "managed",
            imported = listOf("other", "managed"),
            active = "managed",
        )

        val corrupt = decided as LocalActiveRepair.ManagedCorrupt
        assertEquals("managed", corrupt.managedUuid)
    }

    private fun decide(
        managed: String?,
        imported: Collection<String>,
        active: String?,
    ): LocalActiveRepair {
        return runBlocking {
            LocalActiveRepairDecision.decide(
                managedUuid = managed,
                importedUuids = imported,
                activeUuid = active,
                profileDirectory = { tmp.root.resolve(it) },
            )
        }
    }

    private fun writeProfile(uuid: String, config: String) {
        val dir = tmp.newFolder(uuid)
        dir.resolve(ImportedProfileIntegrity.CONFIG_FILE).writeText(config)
    }
}
