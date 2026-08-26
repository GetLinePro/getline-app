package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.ProfileStorageHealth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileStorageInspectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingDirectory_isReported() {
        assertEquals(
            ProfileStorageHealth.MissingDirectory,
            ProfileStorageInspector.inspect(temporaryFolder.root.resolve("missing")),
        )
    }

    @Test
    fun missingConfig_isReported() {
        val profile = temporaryFolder.newFolder("profile")

        assertEquals(
            ProfileStorageHealth.MissingConfig,
            ProfileStorageInspector.inspect(profile),
        )
    }

    @Test
    fun emptyConfig_isReported() {
        val profile = temporaryFolder.newFolder("profile")
        profile.resolve("config.yaml").createNewFile()

        assertEquals(
            ProfileStorageHealth.EmptyConfig,
            ProfileStorageInspector.inspect(profile),
        )
    }

    @Test
    fun nonEmptyConfig_isIntact() {
        val profile = temporaryFolder.newFolder("profile")
        profile.resolve("config.yaml").writeText("proxies: []\n")

        assertEquals(
            ProfileStorageHealth.Intact,
            ProfileStorageInspector.inspect(profile),
        )
    }
}
