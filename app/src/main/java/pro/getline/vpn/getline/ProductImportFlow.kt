package pro.getline.vpn.getline

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.util.BinderDiedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.localproxy.LocalLanProxyOwnerIntegration
import pro.getline.vpn.getlineui.model.GetLineImportStage

/**
 * Owns one primary import/replacement transaction outside the Activity's
 * orchestration: import, foreground-gated activation, binding and cleanup.
 *
 * The Activity remains the host for lifecycle signals, progress rendering and
 * queued external intents. A replacement queued before activation is consumed
 * here so the imported candidate never becomes active or managed first.
 */
internal class ProductImportFlow(
    private val backend: GetLineBackend,
    private val sessions: GetLineSessionRepository,
    private val host: Host,
    private val localProxyOwner: LocalLanProxyOwnerIntegration = LocalLanProxyOwnerIntegration.None,
) {
    interface Host {
        val isForeground: Boolean
        suspend fun awaitForeground()
        fun takeQueuedReplacement(): GetLineSubscriptionDraft?
    }

    sealed class Outcome {
        data class Imported(val id: GetLineSubscriptionId) : Outcome()
        data class Failed(val reason: String) : Outcome()
        data object Cancelled : Outcome()
        data class Superseded(val replacement: GetLineSubscriptionDraft) : Outcome()
        data class ActivationFailed(
            val unavailable: Boolean,
            val retry: ImportRetryTarget?,
        ) : Outcome()
    }

    /** True only when Cancel won the still-pending import wait. */
    fun cancelActiveImport(): Boolean = importTerminal?.tryCancel() == true

    private var importTerminal: ImportAttempt<*>? = null

    suspend fun run(
        request: GetLineSubscriptionDraft,
        subscriptionIdToRemember: String?,
        onImportWaitFinished: () -> Unit = {},
        onProgress: suspend (GetLineImportStage) -> Unit,
    ): Outcome {
        var candidate: GetLineSubscriptionId? = null
        try {
            val imported = try {
                when (
                    val outcome = raceImportAttempt(
                        onLost = { lost -> deleteUnboundCandidate(lost) },
                    ) {
                        runProductImport(request, onProgress)
                    }
                ) {
                    ImportWaitOutcome.Cancelled -> return Outcome.Cancelled
                    is ImportWaitOutcome.Completed -> outcome.value
                }
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                return Outcome.Cancelled
            }

            // The fetch can still be cancelled; activation is a separate
            // foreground wait and must not keep Activity Cancel enabled.
            onImportWaitFinished()

            when (imported) {
                is FetchOutcome.Failed -> return Outcome.Failed(imported.reason)
                is FetchOutcome.Imported -> {
                    candidate = imported.id
                    val replacement = host.takeQueuedReplacement()
                    if (replacement != null) {
                        deleteUnboundCandidate(imported.id)
                        candidate = null
                        return Outcome.Superseded(replacement)
                    }

                    val activated = activateImportedProfileWhenForeground(
                        isForeground = { host.isForeground },
                        awaitForeground = host::awaitForeground,
                        activate = { backend.subscriptions.activateIfImported(imported.id) },
                    )
                    if (!productImportShouldBind(activated)) {
                        deleteUnboundCandidate(imported.id)
                        candidate = null
                        return Outcome.ActivationFailed(
                            unavailable = activated is GetLineBackendResult.Unavailable,
                            retry = importRetryAfterFailedActivation(
                                request = request,
                                subscriptionIdToRemember = subscriptionIdToRemember,
                            ),
                        )
                    }

                    bindActivatedCandidate(
                        id = imported.id,
                        source = request.source,
                        subscriptionIdToRemember = subscriptionIdToRemember,
                    )
                    candidate = null
                    return Outcome.Imported(imported.id)
                }
            }
        } catch (e: CancellationException) {
            candidate?.let { deleteUnboundCandidate(it) }
            Log.i("import_waiter_cancelled")
            throw e
        }
    }

    private suspend fun raceImportAttempt(
        onLost: suspend (GetLineSubscriptionId) -> Unit,
        produce: suspend () -> FetchOutcome,
    ): ImportWaitOutcome<FetchOutcome> {
        val attempt = ImportAttempt<FetchOutcome>()
        importTerminal = attempt
        // Keep the producer independent from the waiter: Cancel must return to
        // the Activity without joining a Binder/HTTP call that ignores cancel.
        val ioJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val value = produce()
                if (!attempt.tryComplete(value)) {
                    withContext(NonCancellable) {
                        if (value is FetchOutcome.Imported) onLost(value.id)
                    }
                }
            } catch (cancelled: CancellationException) {
                attempt.tryCancel()
            } catch (error: Throwable) {
                attempt.tryFail(error)
            }
        }
        var delivered = false
        return try {
            val result = attempt.await()
            delivered = attempt.markDelivered()
            if (!delivered) throw CancellationException("Waiter abandoned")
            result
        } finally {
            if (!delivered) {
                val orphaned = attempt.abandonWaiter()
                if (orphaned is FetchOutcome.Imported) {
                    withContext(NonCancellable) { onLost(orphaned.id) }
                }
            }
            if (importTerminal === attempt) importTerminal = null
            ioJob.cancel()
        }
    }

    private sealed class FetchOutcome {
        data class Imported(val id: GetLineSubscriptionId) : FetchOutcome()
        data class Failed(val reason: String) : FetchOutcome()
    }

    private suspend fun runProductImport(
        request: GetLineSubscriptionDraft,
        onProgress: suspend (GetLineImportStage) -> Unit,
    ): FetchOutcome {
        val terminal = try {
            when (
                val result = backend.subscriptions.importAndCommit(request) { stage ->
                    runCatching { onProgress(stage) }
                }
            ) {
                is GetLineBackendResult.Success -> FetchOutcome.Imported(result.value)
                GetLineBackendResult.Unavailable ->
                    FetchOutcome.Failed(reason = "kind=backend")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: BinderDiedException) {
            FetchOutcome.Failed(reason = "kind=binder_died")
        } catch (t: Throwable) {
            FetchOutcome.Failed(reason = importUnavailableReason(t))
        }
        if (terminal is FetchOutcome.Failed) {
            Log.w("import_terminal unavailable ${terminal.reason}")
        }
        return terminal
    }

    private suspend fun bindActivatedCandidate(
        id: GetLineSubscriptionId,
        source: String?,
        subscriptionIdToRemember: String?,
    ) {
        val previous = commitActivatedProductImport(
            sessions = sessions,
            candidate = id,
            source = source,
            subscriptionIdToRemember = subscriptionIdToRemember,
        )
        sessions.pendingProfileCleanupUuids().forEach { pending ->
            runPendingManagedProfileCleanup(
                pendingUuid = pending,
                managedUuid = id.value,
                canDelete = true,
                stopBeforeDelete = pending == previous,
                stopVpn = backend.vpn::stop,
                deleteManaged = backend.subscriptions::deleteManaged,
                clearPending = sessions::clearPendingProfileCleanup,
            )
        }
        // The binding now names a different managed profile: a replacement is
        // an ownership change like any other.
        localProxyOwner.reconcileOwner()

        Log.i(
            "import_terminal success " +
                "verdict=${sessions.consistencyVerdict()}",
        )
    }

    private suspend fun deleteUnboundCandidate(id: GetLineSubscriptionId) {
        if (id.value == sessions.managedProfileUuid()) return
        withContext(NonCancellable) {
            val result = runCatching { backend.subscriptions.deleteManaged(id) }.getOrNull()
            rememberUnboundCandidateIfDeleteIncomplete(sessions, id, result)
        }
    }
}
