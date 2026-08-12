package com.github.kr328.clash.util

import android.os.DeadObjectException
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
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

/**
 * Binder died after a call that may already have mutated the remote.
 * The cached remote is reset before this is thrown; [block] is not retried.
 */
class BinderDiedException : Exception("binder_died")

suspend fun <T> withProfile(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T = withProfileInternal(retryOnDeadObject = true, context, block)

/**
 * One-shot profile remote for mutating import/commit.
 *
 * On [DeadObjectException]: reset the cached remote, throw [BinderDiedException],
 * never re-enter [block]. Bind-reject fail-fast is unchanged.
 */
suspend fun <T> withProfileOnce(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IProfileManager.() -> T
): T = withProfileInternal(retryOnDeadObject = false, context, block)

private suspend fun <T> withProfileInternal(
    retryOnDeadObject: Boolean,
    context: CoroutineContext,
    block: suspend IProfileManager.() -> T,
): T {
    var lastRemote: IRemoteService? = null
    return runProfileRemoteBlock(
        retryOnDeadObject = retryOnDeadObject,
        onDeadObject = {
            Log.w("Remote services panic")
            lastRemote?.let { Remote.service.remote.reset(it) }
        },
    ) {
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
        lastRemote = remote
        val client = remote.profile()
        withContext(context) { client.block() }
    }
}

/**
 * Dead-object policy for a profile remote call.
 *
 * [onDeadObject] runs before the retry/fail decision so the cached Binder
 * is dropped even when the current mutation is not repeated.
 */
internal suspend fun <T> runProfileRemoteBlock(
    retryOnDeadObject: Boolean,
    onDeadObject: () -> Unit,
    block: suspend () -> T,
): T {
    while (true) {
        try {
            return block()
        } catch (e: DeadObjectException) {
            onDeadObject()
            if (!retryOnDeadObject) {
                throw BinderDiedException()
            }
        }
    }
}
