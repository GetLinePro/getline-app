package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    while (true) {
        // Fail-fast reject is profile-only ([withProfile]). bind() still fails
        // in-flight Resource waiters; Advanced/Proxy must re-park, not crash.
        val remote = try {
            Remote.service.remote.get()
        } catch (_: IllegalStateException) {
            continue
        }
        val client = remote.clash()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T {
    while (true) {
        // #98: bindService false (MIUI process is bad) must not burn the profile
        // timeout and must not sticky-crash Advanced. Re-bind on each profile call
        // so Home/Onboarding Retry can recover when the quarantine lifts — one
        // bind() attempt, then fail-fast if still rejected and unbound.
        if (Remote.service.remote.peek() == null && Remote.service.wasBindRejected()) {
            Remote.service.bind()
            if (Remote.service.remote.peek() == null && Remote.service.wasBindRejected()) {
                throw IllegalStateException("bind_rejected")
            }
        }

        val remote = Remote.service.remote.get()
        val client = remote.profile()

        try {
            return withContext(context) { client.block() }
        } catch (e: DeadObjectException) {
            Log.w("Remote services panic")

            Remote.service.remote.reset(remote)
        }
    }
}
