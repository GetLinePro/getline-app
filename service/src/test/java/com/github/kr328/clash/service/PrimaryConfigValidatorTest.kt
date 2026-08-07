package com.github.kr328.clash.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrimaryConfigValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun read_missingFile_returnsNull() {
        val dir = temporaryFolder.newFolder("empty")
        assertNull(PrimaryConfigValidator.read(dir))
    }

    @Test
    fun writeThenRead_roundTripsOpaqueEtag() {
        val dir = temporaryFolder.newFolder("roundtrip")
        PrimaryConfigValidator.write(dir, "\"opaque-v1\"")
        assertEquals("\"opaque-v1\"", PrimaryConfigValidator.read(dir))
    }

    @Test
    fun invalidate_removesSidecar() {
        val dir = temporaryFolder.newFolder("invalidate")
        PrimaryConfigValidator.write(dir, "etag-a")
        PrimaryConfigValidator.invalidate(dir)
        assertNull(PrimaryConfigValidator.read(dir))
        assertFalse(dir.resolve(PrimaryConfigValidator.FILE_NAME).exists())
    }

    @Test
    fun c1_200WithoutEtag_clearsPreviousValidator() {
        val dir = temporaryFolder.newFolder("c1")
        PrimaryConfigValidator.write(dir, "etag-old")
        dir.resolve("config.yaml").writeText("body-b")

        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = false,
            responseEtag = null,
        )

        assertEquals(ValidatorDisposition.Clear, disposition)
        assertNull(PrimaryConfigValidator.read(dir))
        assertEquals("body-b", dir.resolve("config.yaml").readText())
    }

    @Test
    fun c2_304WithNewEtag_replacesValidator() {
        val dir = temporaryFolder.newFolder("c2-new")
        PrimaryConfigValidator.write(dir, "etag-old")
        dir.resolve("config.yaml").writeText("body-a")

        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = true,
            responseEtag = "etag-new",
        )

        assertEquals(ValidatorDisposition.Set("etag-new"), disposition)
        assertEquals("etag-new", PrimaryConfigValidator.read(dir))
        assertEquals("body-a", dir.resolve("config.yaml").readText())
    }

    @Test
    fun c2_304WithoutEtag_keepsOldValidator() {
        val dir = temporaryFolder.newFolder("c2-keep")
        PrimaryConfigValidator.write(dir, "etag-old")
        dir.resolve("config.yaml").writeText("body-a")

        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = true,
            responseEtag = null,
        )

        assertEquals(ValidatorDisposition.Keep, disposition)
        assertEquals("etag-old", PrimaryConfigValidator.read(dir))
    }

    @Test
    fun c2_304BlankEtag_isIgnoredKeep() {
        val dir = temporaryFolder.newFolder("c2-blank")
        PrimaryConfigValidator.write(dir, "etag-old")

        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = true,
            responseEtag = "  \t",
        )

        assertEquals(ValidatorDisposition.Keep, disposition)
        assertEquals("etag-old", PrimaryConfigValidator.read(dir))
    }

    @Test
    fun ordering_200WithEtag_neverLeavesOldValidatorBesideNewBody() {
        val dir = temporaryFolder.newFolder("ordering")
        PrimaryConfigValidator.write(dir, "etag-A")
        dir.resolve("config.yaml").writeText("body-A")

        // Body replace happens in processing before validator commit (as Refresher does).
        dir.resolve("config.yaml").writeText("body-B")
        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = false,
            responseEtag = "etag-B",
        )

        assertEquals(ValidatorDisposition.Set("etag-B"), disposition)
        assertEquals("body-B", dir.resolve("config.yaml").readText())
        assertEquals("etag-B", PrimaryConfigValidator.read(dir))
        // Intermediate body-B + etag-A is not a stable end state after applyAfterSuccess.
        assertTrue(dir.resolve(PrimaryConfigValidator.FILE_NAME).readText() != "etag-A")
    }

    @Test
    fun ordering_200WriteWouldFailAfterInvalidate_leavesNoValidator() {
        val dir = temporaryFolder.newFolder("ordering-clear")
        PrimaryConfigValidator.write(dir, "etag-A")
        dir.resolve("config.yaml").writeText("body-B")

        // invalidate-before-write: after Clear path body stays, validator gone.
        val disposition = PrimaryConfigValidator.applyAfterSuccess(
            profileDir = dir,
            notModified = false,
            responseEtag = null,
        )
        assertEquals(ValidatorDisposition.Clear, disposition)
        assertEquals("body-B", dir.resolve("config.yaml").readText())
        assertNull(PrimaryConfigValidator.read(dir))
    }

    @Test
    fun disposition_table() {
        assertEquals(
            ValidatorDisposition.Clear,
            PrimaryConfigValidator.disposition(notModified = false, responseEtag = null),
        )
        assertEquals(
            ValidatorDisposition.Set("x"),
            PrimaryConfigValidator.disposition(notModified = false, responseEtag = "x"),
        )
        assertEquals(
            ValidatorDisposition.Keep,
            PrimaryConfigValidator.disposition(notModified = true, responseEtag = null),
        )
        assertEquals(
            ValidatorDisposition.Set("y"),
            PrimaryConfigValidator.disposition(notModified = true, responseEtag = "y"),
        )
    }

    @Test
    fun usableEtag_trimsAndRejectsBlank() {
        assertNull(usableEtag(null))
        assertNull(usableEtag(""))
        assertNull(usableEtag(" \n"))
        assertEquals("W/\"1\"", usableEtag("W/\"1\""))
        assertEquals("\"abc\"", usableEtag("  \"abc\"  "))
        assertTrue(PrimaryConfigValidator.FILE_NAME.isNotBlank())
    }

    /**
     * ProfileProcessor.update commits with deleteRecursively(imported) +
     * copyRecursively(processing → imported). The sidecar must be a regular
     * file inside that tree or conditional GET never fires (silent efficiency loss).
     */
    @Test
    fun sidecar_survivesCommitStyleDirectoryCopy() {
        val processing = temporaryFolder.newFolder("processing")
        val importedRoot = temporaryFolder.newFolder("imported")
        val imported = importedRoot.resolve("uuid-profile")
        processing.resolve("config.yaml").writeText("body-committed")
        PrimaryConfigValidator.write(processing, "\"commit-me\"")

        imported.deleteRecursively()
        processing.copyRecursively(imported, overwrite = true)

        assertEquals("body-committed", imported.resolve("config.yaml").readText())
        assertEquals("\"commit-me\"", PrimaryConfigValidator.read(imported))
        assertTrue(imported.resolve(PrimaryConfigValidator.FILE_NAME).isFile)
    }
}
