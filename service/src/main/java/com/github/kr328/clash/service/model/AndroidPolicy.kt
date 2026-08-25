package com.github.kr328.clash.service.model

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Version 1 of the subscription Android-policy sidecar written next to
 * `config.yaml` after a successful native validate/parse.
 *
 * This is a security input, not a degradable read model. Unknown version,
 * malformed JSON, or an invalid package entry is an error. A missing file is
 * handled by [com.github.kr328.clash.service.AndroidPolicySnapshot], not here.
 */
data class AndroidPolicy(
    val excludedPackages: Set<String>,
) {
    companion object {
        const val FILE_NAME = "android-policy.json"
        const val VERSION = 1
        val EMPTY = AndroidPolicy(emptySet())

        fun fileIn(profileDir: File): File = profileDir.resolve(FILE_NAME)

        fun read(file: File): AndroidPolicy = parse(file.readText(Charsets.UTF_8))

        fun parse(json: String): AndroidPolicy {
            val root = JSONObject(json)
            if (!root.has("version") || root.isNull("version")) {
                throw IllegalArgumentException("android-policy: missing version")
            }
            val version = root.get("version")
            if (version !is Number || version.toInt() != VERSION || version.toDouble() != VERSION.toDouble()) {
                throw IllegalArgumentException("android-policy: unsupported version $version")
            }

            if (!root.has("excludedPackages") || root.isNull("excludedPackages")) {
                throw IllegalArgumentException("android-policy: missing excludedPackages")
            }
            val packages = root.get("excludedPackages")
            if (packages !is JSONArray) {
                throw IllegalArgumentException("android-policy: excludedPackages must be an array")
            }

            val out = LinkedHashSet<String>()
            for (index in 0 until packages.length()) {
                if (packages.isNull(index)) {
                    throw IllegalArgumentException("android-policy: excludedPackages[$index] is null")
                }
                val item = packages.get(index)
                if (item !is String) {
                    throw IllegalArgumentException("android-policy: excludedPackages[$index] must be a string")
                }
                val trimmed = item.trim()
                if (trimmed.isEmpty()) {
                    throw IllegalArgumentException("android-policy: excludedPackages[$index] is empty")
                }
                out.add(trimmed)
            }
            return AndroidPolicy(out)
        }
    }
}
