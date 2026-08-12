package pro.getline.vpn.getline

import com.github.kr328.clash.util.BinderDiedException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.getlineui.model.GetLineImportStage
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs [GetLineSubscriptionRepository.importAndCommit] outside any Activity
 * [kotlinx.coroutines.MainScope] so HOME / renavigation / Activity destroy does
 * not cancel the fetch mid-flight.
 *
 * Concurrency rules:
 * - At most one import job. A new [run] with a different key cancels the prior
 *   job and completes its waiters with [ImportTerminal.Superseded].
 * - Same-key join only while the job is active **and** the deferred is not yet
 *   completed.
 * - Bookkeeping is cleared **before** completing the deferred, so a concurrent
 *   [run] cannot join a just-finished Success.
 * - [onTerminal] and supersede/[reset] share [terminalCommit]: generation cannot
 *   advance between the gen check and the last session write, and a replacement
 *   import cannot start mid-commit. Lock order is always
 *   **terminalCommit → mutex** (never reverse).
 *
 * [onTerminal] is registered by the starter only. Joiners replace progress only.
 */
object GetLineImportCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    /**
     * Serializes terminal persistence with supersede/[reset]. Held for the full
     * [onTerminal] body so generation cannot advance between check and writes.
     */
    private val terminalCommit = Mutex()
    private val generation = AtomicLong(0L)

    private var activeKey: String? = null
    private var job: Job? = null
    private var terminal: CompletableDeferred<ImportTerminal>? = null
    @Volatile
    private var progressSink: (suspend (GetLineImportStage) -> Unit)? = null

    data class ImportRequest(
        val key: String,
        val draft: GetLineSubscriptionDraft,
        val reuseId: GetLineSubscriptionId?,
    )

    sealed class ImportTerminal {
        /** Producer reached a real backend terminal and may be durably committed. */
        sealed class Settled : ImportTerminal() {
            /**
             * Success always clears pending. Among [Unavailable], only
             * [ImportUnavailableKind.BACKEND] is a finished failure;
             * [ImportUnavailableKind.BINDER_DIED] is ambiguous and must keep pending.
             */
            fun clearsPendingImport(): Boolean = when (this) {
                is Success -> true
                is Unavailable -> kind == ImportUnavailableKind.BACKEND
            }
        }

        data class Success(val id: GetLineSubscriptionId) : Settled()
        data class Unavailable(
            val kind: ImportUnavailableKind,
            val reason: String? = null,
        ) : Settled()

        /** A newer import or logout invalidated this waiter; never sent to [onTerminal]. */
        data object Superseded : ImportTerminal()
        /** Explicit user cancellation of this exact active key. */
        data object Cancelled : ImportTerminal()
    }

    fun isInFlight(): Boolean {
        val t = terminal
        return job?.isActive == true && t != null && !t.isCompleted
    }

    suspend fun run(
        request: ImportRequest,
        import: suspend (onProgress: suspend (GetLineImportStage) -> Unit) -> GetLineBackendResult<GetLineSubscriptionId>,
        onProgress: suspend (GetLineImportStage) -> Unit = {},
        onTerminal: suspend (ImportTerminal.Settled) -> Unit = {},
    ): ImportTerminal {
        // Fast path: join under mutex only (no need to wait on terminalCommit).
        val joined = mutex.withLock {
            val existing = terminal
            if (canJoinLocked(existing, request.key)) {
                progressSink = onProgress
                existing
            } else {
                null
            }
        }
        if (joined != null) {
            return joined.await()
        }

        // Start or supersede: wait out mid-onTerminal, then take bookkeeping.
        val deferred = terminalCommit.withLock {
            mutex.withLock {
                val existing = terminal
                if (canJoinLocked(existing, request.key)) {
                    progressSink = onProgress
                    return@withLock existing!!
                }
                supersedeLocked()
                val fresh = CompletableDeferred<ImportTerminal>()
                val gen = generation.incrementAndGet()
                activeKey = request.key
                terminal = fresh
                progressSink = onProgress
                job = scope.launch {
                    try {
                        val terminalResult: ImportTerminal.Settled = try {
                            when (
                                val result = import { stage ->
                                    runCatching { progressSink?.invoke(stage) }
                                }
                            ) {
                                is GetLineBackendResult.Success ->
                                    ImportTerminal.Success(result.value)
                                GetLineBackendResult.Unavailable ->
                                    // Safe discriminator for GL-19 — never a raw exception body.
                                    ImportTerminal.Unavailable(
                                        ImportUnavailableKind.BACKEND,
                                        "kind=backend",
                                    )
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: BinderDiedException) {
                            ImportTerminal.Unavailable(
                                ImportUnavailableKind.BINDER_DIED,
                                "kind=binder_died",
                            )
                        } catch (t: Throwable) {
                            // kind + optional HTTP code only; t.message may hold response bodies.
                            ImportTerminal.Unavailable(
                                ImportUnavailableKind.BACKEND,
                                importUnavailableReason(t),
                            )
                        }

                        // Gen check + full durable commit under terminalCommit.
                        terminalCommit.withLock {
                            if (gen == generation.get()) {
                                try {
                                    onTerminal(terminalResult)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Import result still reaches the waiter. Home repair
                                    // remains the fallback for incomplete local persistence.
                                }
                            }
                        }

                        // Clear before complete so concurrent run() cannot stale-join.
                        mutex.withLock {
                            if (terminal === fresh) {
                                clearBookkeepingLocked()
                            }
                        }
                        fresh.complete(terminalResult)
                    } catch (cancelled: CancellationException) {
                        // reset/supersede normally completed and cleared this producer
                        // already. The NonCancellable fallback also handles an unexpected
                        // parent cancellation without leaking bookkeeping or a waiter.
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (terminal === fresh) {
                                    clearBookkeepingLocked()
                                }
                            }
                            fresh.cancel(cancelled)
                        }
                        throw cancelled
                    }
                }
                fresh
            }
        }

        return deferred.await()
    }

    /**
     * Cancel in-flight work and invalidate [onTerminal] (logout).
     * Waits for any in-progress terminal commit, then advances generation.
     * Waiters unblock with [ImportTerminal.Superseded].
     */
    suspend fun reset() {
        terminalCommit.withLock {
            mutex.withLock {
                supersedeLocked()
            }
        }
    }

    /**
     * Cancel only the still-active import identified by [key]. A completed or
     * replaced import wins the race and remains authoritative.
     */
    suspend fun cancelIfActive(key: String): Boolean {
        return terminalCommit.withLock {
            mutex.withLock {
                if (!canJoinLocked(terminal, key)) return@withLock false
                supersedeLocked(ImportTerminal.Cancelled)
                true
            }
        }
    }

    private fun canJoinLocked(
        existing: CompletableDeferred<ImportTerminal>?,
        key: String,
    ): Boolean {
        return existing != null &&
            !existing.isCompleted &&
            activeKey == key &&
            job?.isActive == true
    }

    /**
     * Must hold [mutex]. When superseding an in-flight terminal commit, caller
     * must also hold [terminalCommit] (see [run], [reset], [cancelIfActive]).
     */
    private fun supersedeLocked(
        waiterTerminal: ImportTerminal = ImportTerminal.Superseded,
    ) {
        generation.incrementAndGet()
        val previous = job
        val previousTerminal = terminal
        previous?.cancel()
        if (previousTerminal != null && !previousTerminal.isCompleted) {
            previousTerminal.complete(waiterTerminal)
        }
        clearBookkeepingLocked()
    }

    private fun clearBookkeepingLocked() {
        job = null
        activeKey = null
        terminal = null
        progressSink = null
    }

    fun importKey(
        source: String?,
        subscriptionIdToRemember: String?,
        reuseId: GetLineSubscriptionId?,
    ): String {
        return listOf(
            source.orEmpty(),
            subscriptionIdToRemember.orEmpty(),
            reuseId?.value.orEmpty(),
        ).joinToString("|")
    }
}

enum class ImportUnavailableKind {
    BACKEND,
    BINDER_DIED,
}

/**
 * Safe [ImportTerminal.Unavailable.reason] for logs/diagnostics.
 * Exception class name + optional HTTP status — never [Throwable.message].
 */
internal fun importUnavailableReason(error: Throwable): String {
    val kind = error.javaClass.simpleName.ifBlank { "Throwable" }
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < 6) {
        val failure = current as? GetLineAuthException.HttpFailure
        if (failure != null) {
            return "kind=$kind code=${failure.code}"
        }
        current = current.cause
        depth++
    }
    return "kind=$kind"
}
