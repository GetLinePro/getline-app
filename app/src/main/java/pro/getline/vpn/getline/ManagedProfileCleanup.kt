package pro.getline.vpn.getline

import com.github.kr328.clash.common.log.Log

internal enum class ManagedProfileCleanupResult {
    None,
    ProtectedManaged,
    DeferredReplacement,
    Deleted,
    NotFound,
    Unavailable,
}

/**
 * Consume one durable old-profile tombstone.
 *
 * [canDelete] must be true only after the replacement is proven imported/active,
 * or after an explicit logout has stopped the VPN and owns removal of all profiles.
 * [stopBeforeDelete] is selected by the caller only when the old UUID may still
 * be active. A failed stop is observable but does not skip deletion. Completion
 * clears only the UUID this attempt read, so a newer tombstone is not lost.
 */
internal suspend fun runPendingManagedProfileCleanup(
    pendingUuid: String?,
    managedUuid: String?,
    canDelete: Boolean,
    stopBeforeDelete: Boolean,
    stopVpn: () -> Unit,
    deleteManaged: suspend (GetLineSubscriptionId) ->
        GetLineBackendResult<ManagedProfileDeleteOutcome>,
    clearPending: (String) -> Unit,
): ManagedProfileCleanupResult {
    val pending = pendingUuid?.takeIf { it.isNotBlank() }
        ?: return ManagedProfileCleanupResult.None

    // A corrupt/stale tombstone must never delete the profile currently owned
    // by the product flow. Drop it so every repair stays safe and idempotent.
    if (pending == managedUuid) {
        clearPending(pending)
        Log.w("profile_cleanup outcome=protected_managed stop=na")
        return ManagedProfileCleanupResult.ProtectedManaged
    }

    if (!canDelete) {
        Log.i("profile_cleanup outcome=deferred_no_replacement stop=na")
        return ManagedProfileCleanupResult.DeferredReplacement
    }

    val stopOutcome = if (stopBeforeDelete) {
        if (runCatching { stopVpn() }.isSuccess) "ok" else "failed"
    } else {
        "na"
    }

    return when (val result = deleteManaged(GetLineSubscriptionId(pending))) {
        GetLineBackendResult.Unavailable -> {
            Log.w("profile_cleanup outcome=unavailable stop=$stopOutcome")
            ManagedProfileCleanupResult.Unavailable
        }
        is GetLineBackendResult.Success -> when (result.value) {
            ManagedProfileDeleteOutcome.Deleted -> {
                clearPending(pending)
                Log.i("profile_cleanup outcome=deleted stop=$stopOutcome")
                ManagedProfileCleanupResult.Deleted
            }
            ManagedProfileDeleteOutcome.NotFound -> {
                clearPending(pending)
                Log.i("profile_cleanup outcome=not_found stop=$stopOutcome")
                ManagedProfileCleanupResult.NotFound
            }
        }
    }
}
