package pro.getline.vpn.util

import android.os.DeadObjectException
import pro.getline.vpn.common.log.Log
import pro.getline.vpn.remote.Remote
import pro.getline.vpn.service.remote.IClashManager
import pro.getline.vpn.service.remote.IProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

suspend fun <T> withClash(
    context: CoroutineContext = Dispatchers.IO,
    block: suspend IClashManager.() -> T
): T {
    while (true) {
        val remote = Remote.service.remote.get()
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
