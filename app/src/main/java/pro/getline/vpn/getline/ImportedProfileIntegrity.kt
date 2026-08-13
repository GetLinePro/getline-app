package pro.getline.vpn.getline

import java.io.File

/**
 * Cheap structural check of an imported profile directory.
 *
 * Does not parse YAML or call Clash. A non-empty `config.yaml` is the
 * minimum that [com.github.kr328.clash.service.clash.module.ConfigurationModule]
 * can load; DAO existence alone is not enough after a crash between
 * delete and copy in ProfileProcessor.
 */
internal object ImportedProfileIntegrity {
    const val CONFIG_FILE = "config.yaml"

    enum class Verdict {
        Intact,
        MissingDirectory,
        MissingConfig,
        EmptyConfig,
        ;

        val logToken: String
            get() = when (this) {
                Intact -> "na"
                MissingDirectory -> "missing_dir"
                MissingConfig -> "missing_config"
                EmptyConfig -> "empty_config"
            }
    }

    fun inspect(profileDir: File): Verdict {
        if (!profileDir.isDirectory) return Verdict.MissingDirectory
        val config = profileDir.resolve(CONFIG_FILE)
        if (!config.isFile) return Verdict.MissingConfig
        if (config.length() <= 0L) return Verdict.EmptyConfig
        return Verdict.Intact
    }
}
