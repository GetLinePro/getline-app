package pro.getline.vpn.getline.refresh

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineSubscriptionId
import java.util.concurrent.TimeUnit

/**
 * Periodic refresh of the GetLine-managed subscription profile.
 *
 * Cadence is a product constant, not `profile-update-interval`. WorkManager
 * treats the hour as best-effort / inexact.
 *
 * WorkManager's default initializer also runs in `:background`. Scheduling
 * is process-gated; the WorkDatabase is shared. Do not switch to on-demand
 * init without replacing [workManagerOrNull] — [WorkManager.isInitialized]
 * would stay false and [ensure] would no-op forever.
 *
 * Migration constraint, accepted deliberately: work is enqueued only from
 * [pro.getline.vpn.getline.auth.GetLineSessionRepository.rememberManagedProfile]
 * and the launcher route. WorkManager itself carries already enqueued work
 * across reboot and APK replace, so this affects one case only — a binding
 * created before this version shipped gets no background refresh until the
 * app is opened once.
 * The alarm-based `MY_PACKAGE_REPLACED` path that used to cover it is gone
 * and is not worth rebuilding for a two-user alpha.
 */
object ManagedProfileRefresh {
    const val INTERVAL_HOURS = 1L
    const val UNIQUE_WORK_NAME = "pro.getline.vpn.managed_profile_refresh"

    fun sync(context: Context, managedProfileUuid: String?) {
        if (managedProfileUuid.isNullOrBlank()) {
            cancel(context)
        } else {
            ensure(context)
        }
    }

    fun ensure(context: Context) {
        val workManager = workManagerOrNull(context) ?: return
        val request = PeriodicWorkRequestBuilder<ProfileRefreshWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        workManagerOrNull(context)?.cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun workManagerOrNull(context: Context): WorkManager? {
        if (!WorkManager.isInitialized()) return null
        return WorkManager.getInstance(context.applicationContext)
    }
}

internal sealed class ManagedProfileRefreshOutcome {
    object NoBinding : ManagedProfileRefreshOutcome()

    data class Terminal(
        val result: ConfigUpdateResult,
    ) : ManagedProfileRefreshOutcome()
}

/**
 * One worker tick: re-read the managed uuid and refresh exactly that profile.
 * Missing / unparseable / gone binding is success/no-op, not a retry.
 */
internal suspend fun refreshManagedProfile(
    managedUuid: String?,
    update: suspend (GetLineSubscriptionId) -> ConfigUpdateResult,
): ManagedProfileRefreshOutcome {
    val raw = managedUuid?.takeIf { it.isNotBlank() }
        ?: return ManagedProfileRefreshOutcome.NoBinding
    val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
        ?: return ManagedProfileRefreshOutcome.NoBinding
    return ManagedProfileRefreshOutcome.Terminal(
        update(GetLineSubscriptionId(uuid.toString())),
    )
}
