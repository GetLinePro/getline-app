package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.AndroidPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidPolicySnapshotTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val first = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val second = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @Test
    fun missingOnFirstSeenUuid_isLegacyEmpty() {
        val warnings = mutableListOf<String>()
        val snapshot = AndroidPolicySnapshot { warnings += it }

        val policy = snapshot.resolve(first, temporaryFolder.root.resolve("missing.json"))

        assertEquals(AndroidPolicy.EMPTY, policy)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun missingOnSameUuid_keepsLastKnownGood() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val warnings = mutableListOf<String>()
        val snapshot = AndroidPolicySnapshot { warnings += it }

        assertEquals(setOf("com.example.maps"), snapshot.resolve(first, sidecar).excludedPackages)

        sidecar.delete()
        val kept = snapshot.resolve(first, sidecar)

        assertEquals(setOf("com.example.maps"), kept.excludedPackages)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains(first.toString()))
    }

    @Test
    fun missingOnUuidChange_isLegacyEmpty() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val snapshot = AndroidPolicySnapshot()

        snapshot.resolve(first, sidecar)

        val next = snapshot.resolve(second, dir.resolve("absent.json"))

        assertEquals(AndroidPolicy.EMPTY, next)
    }

    @Test
    fun validSidecarReplacesSnapshot() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val snapshot = AndroidPolicySnapshot()

        snapshot.resolve(first, sidecar)
        writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.mail"]}""")

        assertEquals(setOf("com.example.mail"), snapshot.resolve(first, sidecar).excludedPackages)
    }

    @Test
    fun unknownVersion_failStops() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":2,"excludedPackages":[]}""")
        val snapshot = AndroidPolicySnapshot()

        try {
            snapshot.resolve(first, sidecar)
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message, error.message?.contains("unsupported version") == true)
            return
        }
        throw AssertionError("expected fail-stop")
    }

    @Test
    fun malformedJson_failStops() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, "{")
        val snapshot = AndroidPolicySnapshot()

        try {
            snapshot.resolve(first, sidecar)
        } catch (_: Exception) {
            return
        }
        throw AssertionError("expected fail-stop")
    }

    @Test
    fun malformedJsonOnSameUuid_keepsLastKnownGood() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val warnings = mutableListOf<String>()
        val snapshot = AndroidPolicySnapshot { warnings += it }

        assertEquals(setOf("com.example.maps"), snapshot.resolve(first, sidecar).excludedPackages)

        writeSidecar(dir, "{")
        val kept = snapshot.resolve(first, sidecar)

        assertEquals(setOf("com.example.maps"), kept.excludedPackages)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("unreadable"))
        assertTrue(warnings.single().contains(first.toString()))
    }

    @Test
    fun unknownVersionOnSameUuid_keepsLastKnownGood() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val snapshot = AndroidPolicySnapshot()

        snapshot.resolve(first, sidecar)
        writeSidecar(dir, """{"version":2,"excludedPackages":[]}""")

        assertEquals(setOf("com.example.maps"), snapshot.resolve(first, sidecar).excludedPackages)
    }

    @Test
    fun malformedJsonOnUuidChange_failStops() {
        val dir = temporaryFolder.newFolder("profile")
        val sidecar = writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")
        val snapshot = AndroidPolicySnapshot()

        snapshot.resolve(first, sidecar)
        writeSidecar(dir, "{")

        try {
            snapshot.resolve(second, sidecar)
        } catch (_: Exception) {
            return
        }
        throw AssertionError("expected fail-stop")
    }

    @Test
    fun nullActiveProfile_isEmpty() {
        val snapshot = AndroidPolicySnapshot()
        val dir = temporaryFolder.newFolder("profile")
        writeSidecar(dir, """{"version":1,"excludedPackages":["com.example.maps"]}""")

        assertEquals(AndroidPolicy.EMPTY, snapshot.resolve(null, AndroidPolicy.fileIn(dir)))
    }

    private fun writeSidecar(dir: java.io.File, json: String): java.io.File {
        val file = AndroidPolicy.fileIn(dir)
        file.writeText(json)
        return file
    }
}
