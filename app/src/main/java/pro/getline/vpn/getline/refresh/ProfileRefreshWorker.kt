package pro.getline.vpn.getline.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import pro.getline.vpn.cmfa.updateImportedProfileSilently
import pro.getline.vpn.getline.auth.GetLineSessionStore

class ProfileRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val managedUuid = GetLineSessionStore(applicationContext).managedProfileUuid
        return try {
            refreshManagedProfile(managedUuid) { uuid ->
                updateImportedProfileSilently(applicationContext, uuid)
            }
            Result.success()
        } catch (e: TimeoutCancellationException) {
            Log.w("managed_profile_refresh outcome=retry ex=timeout")
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(
                "managed_profile_refresh outcome=retry " +
                    "ex=${e.javaClass.simpleName}",
            )
            Result.retry()
        }
    }
}
