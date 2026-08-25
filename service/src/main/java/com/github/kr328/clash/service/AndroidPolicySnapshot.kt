package com.github.kr328.clash.service

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.model.AndroidPolicy
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * Service-lifetime last-known-good of the active profile's Android policy.
 *
 * The imported directory commit is sequential delete-then-copy. A reconcile
 * that races that window must not treat a temporarily missing or torn sidecar
 * as a policy change for a UUID that was already observed. A different UUID
 * with a missing sidecar is a legacy profile and gets empty policy.
 *
 * Malformed or unsupported JSON fails closed only when this process has no
 * last-known-good for that UUID. There is no disk cache.
 */
internal class AndroidPolicySnapshot(
    private val warn: (String) -> Unit = { Log.w(it) },
) {
    private var uuid: UUID? = null
    private var policy: AndroidPolicy = AndroidPolicy.EMPTY

    fun resolve(activeUuid: UUID?, sidecar: File?): AndroidPolicy {
        if (activeUuid == null) {
            uuid = null
            policy = AndroidPolicy.EMPTY
            return policy
        }

        return try {
            if (sidecar == null || !sidecar.isFile) {
                missing(activeUuid)
            } else {
                val parsed = AndroidPolicy.read(sidecar)
                uuid = activeUuid
                policy = parsed
                parsed
            }
        } catch (_: FileNotFoundException) {
            missing(activeUuid)
        } catch (error: Exception) {
            if (uuid == activeUuid) {
                warn("android policy sidecar unreadable for $activeUuid, keeping last-known-good")
                policy
            } else {
                throw error
            }
        }
    }

    private fun missing(activeUuid: UUID): AndroidPolicy {
        if (uuid == activeUuid) {
            warn("android policy sidecar missing for $activeUuid, keeping last-known-good")
            return policy
        }
        uuid = activeUuid
        policy = AndroidPolicy.EMPTY
        return policy
    }
}
