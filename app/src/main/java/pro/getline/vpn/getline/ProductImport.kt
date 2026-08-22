package pro.getline.vpn.getline

import kotlinx.coroutines.CompletableDeferred
import pro.getline.vpn.getline.auth.GetLineSessionRepository

internal sealed class ImportWaitOutcome<out T> {
    data class Completed<T>(val value: T) : ImportWaitOutcome<T>()
    data object Cancelled : ImportWaitOutcome<Nothing>()
}

/**
 * One in-flight import wait. Producer and Cancel race [tryComplete] /
 * [tryCancel]; first `complete()` owns the terminal. Session cleanup is
 * allowed only when Cancel wins. Do not use Job.isActive for this race.
 *
 * Delivery is a second atomic: [markDelivered] vs [abandonWaiter]. A
 * Completed terminal that the waiter never consumed is returned from
 * [abandonWaiter] so the caller can tombstone; [tryCancel] alone cannot
 * see that value.
 */
internal class ImportAttempt<T> {
    private val outcome = CompletableDeferred<ImportWaitOutcome<T>>()
    private val delivery = CompletableDeferred<Boolean>()
    private val lock = Any()
    private var completedValue: T? = null

    fun tryComplete(value: T): Boolean = synchronized(lock) {
        if (!outcome.complete(ImportWaitOutcome.Completed(value))) return false
        completedValue = value
        true
    }

    fun tryCancel(): Boolean = synchronized(lock) {
        outcome.complete(ImportWaitOutcome.Cancelled)
    }

    fun tryFail(error: Throwable): Boolean = synchronized(lock) {
        outcome.completeExceptionally(error)
    }

    suspend fun await(): ImportWaitOutcome<T> = outcome.await()

    /** Waiter will handle [await]'s result. False if [abandonWaiter] already won. */
    fun markDelivered(): Boolean = delivery.complete(true)

    /**
     * Waiter is gone. Incomplete producer becomes Cancelled so a late
     * [tryComplete] loses and goes to onLost. If producer already completed
     * but delivery never happened, returns that value for onLost.
     */
    fun abandonWaiter(): T? = synchronized(lock) {
        outcome.complete(ImportWaitOutcome.Cancelled)
        if (!delivery.complete(false)) return null
        completedValue
    }
}

internal data class ImportRetryTarget(
    val request: GetLineSubscriptionDraft,
    val subscriptionIdToRemember: String?,
)

/**
 * Failed activation retries the import, not the unbound candidate.
 * Both retry targets must keep the account subscription ID or the next
 * commit is classified as link-only and the post-login step reopens.
 */
internal fun importRetryAfterFailedActivation(
    request: GetLineSubscriptionDraft?,
    subscriptionIdToRemember: String?,
): ImportRetryTarget? {
    if (request == null) return null
    return ImportRetryTarget(
        request = request,
        subscriptionIdToRemember = subscriptionIdToRemember,
    )
}

/** Cancel of preferred-load must start B, not drop it after taking it off the channel. */
internal fun preferredLoadCancelStartsQueuedImport(
    finishing: Boolean,
    hasQueuedImport: Boolean,
): Boolean = !finishing && hasQueuedImport

/**
 * Best-effort delete of an unbound candidate. Unavailable/exception must
 * tombstone the UUID so later repair/logout can retry; do not forget it.
 */
internal fun rememberUnboundCandidateIfDeleteIncomplete(
    sessions: GetLineSessionRepository,
    candidate: GetLineSubscriptionId,
    result: GetLineBackendResult<ManagedProfileDeleteOutcome>?,
) {
    val uuid = candidate.value.takeIf { it.isNotBlank() } ?: return
    if (uuid == sessions.managedProfileUuid()) return
    val completed = result is GetLineBackendResult.Success &&
        (
            result.value is ManagedProfileDeleteOutcome.Deleted ||
                result.value is ManagedProfileDeleteOutcome.NotFound
        )
    if (completed) return
    sessions.rememberPendingProfileCleanup(uuid)
}

internal fun productImportShouldBind(
    activated: GetLineBackendResult<Boolean>,
): Boolean = activated is GetLineBackendResult.Success && activated.value

/**
 * Bind the activated candidate. Returns the prior managed UUID when it was
 * tombstoned for cleanup. Does not delete profiles.
 */
internal fun commitActivatedProductImport(
    sessions: GetLineSessionRepository,
    candidate: GetLineSubscriptionId,
    source: String?,
    subscriptionIdToRemember: String?,
): String? {
    val previous = sessions.managedProfileUuid()
        ?.takeIf { it.isNotBlank() && it != candidate.value }
    if (previous != null) {
        sessions.rememberPendingProfileCleanup(previous)
    }
    sessions.rememberManagedProfile(candidate.value, source)
    subscriptionIdToRemember?.takeIf { it.isNotBlank() }?.let {
        sessions.rememberSubscription(it)
    }
    return previous
}

internal fun abandonPostLoginImportSession(
    sessions: GetLineSessionRepository,
    linkOnlySignIn: Boolean,
) {
    if (linkOnlySignIn) {
        sessions.discardSessionKeepingSubscription()
    } else {
        sessions.logout()
    }
}
