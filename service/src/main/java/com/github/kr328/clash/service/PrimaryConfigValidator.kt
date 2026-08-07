package com.github.kr328.clash.service

import com.github.kr328.clash.common.log.Log
import java.io.File
import java.io.IOException

/**
 * Sidecar validator (opaque ETag) for a committed primary-config representation.
 *
 * Lives in the profile directory next to `config.yaml`. Commit protocol after a
 * successful 200 validation:
 * 1. [invalidate] old validator
 * 2. replace body (processing → imported)
 * 3. [write] new validator only if the response carried an ETag
 *
 * On 304: body is not replaced; [applyAfterSuccess] keeps or updates the sidecar.
 */
internal object PrimaryConfigValidator {
    const val FILE_NAME = "primary.etag"

    fun read(profileDir: File): String? {
        val file = profileDir.resolve(FILE_NAME)
        if (!file.isFile) return null
        return usableEtag(file.readText(Charsets.UTF_8))
    }

    fun invalidate(profileDir: File) {
        val file = profileDir.resolve(FILE_NAME)
        if (file.exists() && !file.delete()) {
            throw IOException("Unable to invalidate primary config validator at ${file.path}")
        }
    }

    fun write(profileDir: File, etag: String) {
        require(etag.isNotEmpty()) { "empty etag" }
        profileDir.mkdirs()
        val target = profileDir.resolve(FILE_NAME)
        val temporary = File(profileDir, "$FILE_NAME.tmp")
        temporary.writeText(etag, Charsets.UTF_8)
        if (target.exists() && !target.delete()) {
            temporary.delete()
            throw IOException("Unable to replace primary config validator at ${target.path}")
        }
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            if (!temporary.delete()) {
                Log.w("Unable to delete primary config validator temp file")
            }
        }
    }

    /**
     * After successful validate/process for a primary-config fetch.
     * @param notModified true when the transport returned 304
     * @param responseEtag usable ETag from the response, or null if absent/garbage
     */
    fun applyAfterSuccess(
        profileDir: File,
        notModified: Boolean,
        responseEtag: String?,
    ): ValidatorDisposition {
        val disposition = disposition(notModified, responseEtag)
        when (disposition) {
            ValidatorDisposition.Keep -> Unit
            ValidatorDisposition.Clear -> invalidate(profileDir)
            is ValidatorDisposition.Set -> {
                // 200 path: always clear first so body B + etag A is unreachable
                // even if write fails after invalidate.
                if (!notModified) {
                    invalidate(profileDir)
                }
                write(profileDir, disposition.etag)
            }
        }
        return disposition
    }

    fun disposition(notModified: Boolean, responseEtag: String?): ValidatorDisposition {
        val etag = usableEtag(responseEtag)
        return when {
            etag != null -> ValidatorDisposition.Set(etag)
            notModified -> ValidatorDisposition.Keep
            else -> ValidatorDisposition.Clear
        }
    }
}

internal sealed class ValidatorDisposition {
    data object Keep : ValidatorDisposition()
    data object Clear : ValidatorDisposition()
    data class Set(val etag: String) : ValidatorDisposition()

    fun logLabel(): String = when (this) {
        Keep -> "kept"
        Clear -> "cleared"
        is Set -> "updated"
    }
}
