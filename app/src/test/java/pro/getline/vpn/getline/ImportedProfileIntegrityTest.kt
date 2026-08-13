package pro.getline.vpn.getline

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImportedProfileIntegrityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun missingDirectory_isMissingDirectory() {
        val dir = tmp.root.resolve("gone")
        assertEquals(
            ImportedProfileIntegrity.Verdict.MissingDirectory,
            ImportedProfileIntegrity.inspect(dir),
        )
    }

    @Test
    fun emptyDirectory_isMissingConfig() {
        val dir = tmp.newFolder("empty")
        assertEquals(
            ImportedProfileIntegrity.Verdict.MissingConfig,
            ImportedProfileIntegrity.inspect(dir),
        )
    }

    @Test
    fun missingConfigFile_isMissingConfig() {
        val dir = tmp.newFolder("partial")
        dir.resolve("providers").mkdir()
        assertEquals(
            ImportedProfileIntegrity.Verdict.MissingConfig,
            ImportedProfileIntegrity.inspect(dir),
        )
    }

    @Test
    fun emptyConfigFile_isEmptyConfig() {
        val dir = tmp.newFolder("empty-config")
        dir.resolve(ImportedProfileIntegrity.CONFIG_FILE).createNewFile()
        assertEquals(
            ImportedProfileIntegrity.Verdict.EmptyConfig,
            ImportedProfileIntegrity.inspect(dir),
        )
    }

    @Test
    fun nonEmptyConfig_isIntact() {
        val dir = tmp.newFolder("ok")
        dir.resolve(ImportedProfileIntegrity.CONFIG_FILE).writeText("proxies: []\n")
        assertEquals(
            ImportedProfileIntegrity.Verdict.Intact,
            ImportedProfileIntegrity.inspect(dir),
        )
    }
}
