package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.ProfileStorageHealth
import java.io.File

internal object ProfileStorageInspector {
    private const val CONFIG_FILE = "config.yaml"

    fun inspect(profileDir: File): ProfileStorageHealth {
        if (!profileDir.isDirectory) return ProfileStorageHealth.MissingDirectory
        val config = profileDir.resolve(CONFIG_FILE)
        if (!config.isFile) return ProfileStorageHealth.MissingConfig
        if (config.length() <= 0L) return ProfileStorageHealth.EmptyConfig
        return ProfileStorageHealth.Intact
    }
}
