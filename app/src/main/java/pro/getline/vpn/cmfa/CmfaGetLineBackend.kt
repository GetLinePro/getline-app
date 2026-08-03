package pro.getline.vpn.cmfa

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import pro.getline.vpn.GetLineHomeActivity
import pro.getline.vpn.GetLineOnboardingActivity
import com.github.kr328.clash.MainActivity
import pro.getline.vpn.cmfa.servers.CmfaVpnServerSelectionRepository
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.FetchStatus
import pro.getline.vpn.getlineui.model.GetLineImportStage
import pro.getline.vpn.getlineui.model.GetLineTraffic
import pro.getline.vpn.getline.ActiveProfilePolicy
import pro.getline.vpn.getline.GetLineBackend
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineNavigation
import pro.getline.vpn.getline.GetLineSession
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionRepository
import pro.getline.vpn.getline.GetLineSubscriptionSnapshot
import pro.getline.vpn.getline.GetLineSubscriptionSummary
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.GetLineVpnController
import pro.getline.vpn.getline.LocalActiveRepair
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

class CmfaGetLineBackend(
    activity: Activity,
) : GetLineBackend {
    override val subscriptions: GetLineSubscriptionRepository =
        CmfaGetLineSubscriptionRepository()
    override val vpn: GetLineVpnController = CmfaGetLineVpnController(activity)
    override val servers: VpnServerSelectionRepository =
        CmfaVpnServerSelectionRepository(activity)
    override val navigation: GetLineNavigation = CmfaGetLineNavigation(activity)
}

private class CmfaGetLineSubscriptionRepository : GetLineSubscriptionRepository {
    override suspend fun snapshot(): GetLineBackendResult<GetLineSubscriptionSnapshot> {
        return callProfileBackend {
            withProfile {
                GetLineSubscriptionSnapshot(
                    active = queryActive()
                        ?.takeIf { it.imported }
                        ?.toGetLineSummary(),
                    hasImported = queryAll().any { it.imported },
                )
            }
        }
    }

    override suspend fun hasImported(): GetLineBackendResult<Boolean> {
        return callProfileBackend {
            withProfile {
                queryAll().any { it.imported }
            }
        }
    }

    override suspend fun hasActiveImported(): GetLineBackendResult<Boolean> {
        return callProfileBackend {
            withProfile {
                queryActive()?.imported == true
            }
        }
    }

    override suspend fun createPending(
        draft: GetLineSubscriptionDraft,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        return createOrUpdatePending(draft, reuseId = null)
    }

    override suspend fun createOrUpdatePending(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        return callProfileBackend {
            withProfile {
                val existingUuid = reuseId
                    ?.toUuid()
                    ?.let { uuid -> queryByUUID(uuid)?.uuid }

                val uuid = existingUuid ?: create(draft.type.toCmfaType(), draft.name)
                if (draft.source != null) {
                    patch(
                        uuid,
                        draft.name,
                        draft.source,
                        draft.interval,
                        null,
                    )
                }
                GetLineSubscriptionId(uuid.toString())
            }
        }
    }

    override suspend fun activateIfImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Boolean> {
        return callProfileBackend {
            withProfile {
                queryByUUID(id.toUuid())
                    ?.takeIf { it.imported }
                    ?.also { setActive(it) } != null
            }
        }
    }

    override suspend fun ensureActiveImported(
        managedUuid: String?,
    ): GetLineBackendResult<Boolean> {
        return when (val repaired = repairLocalActive(managedUuid)) {
            GetLineBackendResult.Unavailable -> GetLineBackendResult.Unavailable
            is GetLineBackendResult.Success ->
                GetLineBackendResult.Success(repaired.value is LocalActiveRepair.Ready)
        }
    }

    override suspend fun repairLocalActive(
        managedUuid: String?,
    ): GetLineBackendResult<LocalActiveRepair> {
        return callProfileBackend {
            withProfile {
                val managed = managedUuid?.takeIf { it.isNotBlank() }
                val imported = queryAll().filter { it.imported }
                val active = queryActive()?.takeIf { it.imported }
                val managedIsImported = managed != null &&
                    imported.any { it.uuid.toString() == managed }

                val target = ActiveProfilePolicy.resolveUuidToActivate(
                    activeUuid = active?.uuid?.toString(),
                    importedUuids = imported.map { it.uuid.toString() },
                    managedUuid = managed,
                )
                if (target != null) {
                    val profile = queryByUUID(UUID.fromString(target))
                        ?.takeIf { it.imported }
                    if (profile != null) {
                        setActive(profile)
                        return@withProfile LocalActiveRepair.Ready(profile.uuid.toString())
                    }
                }

                val after = queryActive()?.takeIf { it.imported }
                if (after != null) {
                    LocalActiveRepair.Ready(after.uuid.toString())
                } else {
                    LocalActiveRepair.ManagedAbsent(
                        managedUuid = managed,
                        managedIsImported = managedIsImported,
                    )
                }
            }
        }
    }

    override suspend fun reimportAndActivate(
        draft: GetLineSubscriptionDraft,
        managedId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        // activate=true keeps orphan cleanup across setActive (same try block as commit).
        return callProfileBackend(op = "reimport", timeoutMs = REIMPORT_TIMEOUT_MS) {
            withProfile {
                importPending(
                    draft = draft,
                    reuseId = managedId,
                    onProgress = {},
                    activate = true,
                    diagnosticOp = null,
                )
            }
        }
    }

    override suspend fun importAndCommit(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId?,
        onProgress: suspend (GetLineImportStage) -> Unit,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        val op = UUID.randomUUID().toString().take(8)
        val startedAt = SystemClock.elapsedRealtime()
        var unavailableKind = "unknown"
        Log.i("profile_import start op=$op reuse=${if (reuseId == null) 0 else 1}")

        val result = callProfileBackend(
            timeoutMs = REIMPORT_TIMEOUT_MS,
            onUnavailable = { kind -> unavailableKind = kind },
        ) {
            withProfile {
                Log.i("profile_import stage=remote_acquired op=$op")
                importPending(
                    draft = draft,
                    reuseId = reuseId,
                    onProgress = onProgress,
                    activate = false,
                    diagnosticOp = op,
                )
            }
        }

        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        when (result) {
            is GetLineBackendResult.Success ->
                Log.i("profile_import end op=$op outcome=ok elapsed_ms=$elapsedMs")
            GetLineBackendResult.Unavailable ->
                Log.w(
                    // kind last: it now carries free-form message text.
                    "profile_import end op=$op outcome=unavailable " +
                        "elapsed_ms=$elapsedMs kind=$unavailableKind",
                )
        }
        return result
    }

    /**
     * Shared headless import. [createdOrphan] is cleared only after the full path
     * succeeds — including [setActive] when [activate] is true — so a failed
     * activation still deletes a UUID minted by this call.
     */
    private suspend fun IProfileManager.importPending(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId?,
        onProgress: suspend (GetLineImportStage) -> Unit,
        activate: Boolean,
        diagnosticOp: String?,
    ): GetLineSubscriptionId {
        // File profiles ship an empty config.yaml; ProfileProcessor uses force=false
        // so commit never fetches draft.source and always fails. Advanced BrowseFiles
        // is the supported File path. ExternalControl sometimes tags a remote URL as
        // type=file — promote those to Url before create. Non-URL File: refuse early.
        val importType = resolveHeadlessImportType(draft)

        val existingUuid = reuseId
            ?.toUuid()
            ?.let { uuid -> queryByUUID(uuid)?.uuid }
        // Only delete on failure when we minted a brand-new UUID this call.
        var createdOrphan: UUID? = null
        val uuid = existingUuid ?: create(importType.toCmfaType(), draft.name).also {
            createdOrphan = it
        }
        try {
            if (draft.source != null) {
                patch(
                    uuid,
                    draft.name,
                    draft.source,
                    draft.interval,
                    null,
                )
            }
            diagnosticOp?.let {
                Log.i(
                    "profile_import stage=profile_prepared op=$it " +
                        "reused=${if (existingUuid == null) 0 else 1}",
                )
            }
            // IFetchObserver.updateStatus is synchronous — do not launch on an
            // arbitrary scope (progress would outlive import cancellation).
            diagnosticOp?.let { Log.i("profile_import stage=commit_begin op=$it") }
            coroutineScope {
                val stages = Channel<GetLineImportStage>(Channel.CONFLATED)
                var lastDiagnosticAction: FetchStatus.Action? = null
                val progressJob = launch {
                    for (stage in stages) {
                        onProgress(stage)
                    }
                }
                try {
                    commit(uuid) { status ->
                        Log.d(fetchStatusDiagnosticLine(status))
                        if (diagnosticOp != null && status.action != lastDiagnosticAction) {
                            lastDiagnosticAction = status.action
                            Log.i(
                                "profile_import fetch op=$diagnosticOp " +
                                    "action=${status.action}",
                            )
                        }
                        mapFetchActionToImportStage(status.action)
                            ?.let { stages.trySend(it) }
                    }
                } finally {
                    stages.close()
                    progressJob.join()
                }
            }
            diagnosticOp?.let { Log.i("profile_import stage=commit_returned op=$it") }
            val imported = queryByUUID(uuid)?.takeIf { it.imported }
                ?: throw IllegalStateException("profile not imported after commit")
            diagnosticOp?.let { Log.i("profile_import stage=verified op=$it") }
            if (activate) {
                setActive(imported)
            }
            // Clear only after the full requested path (import ± activate) succeeded.
            createdOrphan = null
            return GetLineSubscriptionId(uuid.toString())
        } catch (e: Throwable) {
            val orphan = createdOrphan
            if (orphan != null) {
                // Parent may already be cancelled (withTimeout). Cleanup must not
                // ride that cancellation or Binder delete is dropped silently.
                val cleanup = withContext(NonCancellable) {
                    runCatching {
                        withTimeout(ORPHAN_CLEANUP_TIMEOUT_MS) {
                            delete(orphan)
                        }
                    }
                }
                diagnosticOp?.let {
                    Log.i(
                        "profile_import cleanup op=$it " +
                            "outcome=${if (cleanup.isSuccess) "ok" else "failed"}",
                    )
                }
            }
            throw e
        }
    }

    /**
     * Headless import needs a fetchable remote source (Url). File without an
     * http(s) source cannot be committed product-side.
     */
    private fun resolveHeadlessImportType(
        draft: GetLineSubscriptionDraft,
    ): GetLineSubscriptionType {
        return when (draft.type) {
            GetLineSubscriptionType.Url -> GetLineSubscriptionType.Url
            GetLineSubscriptionType.File -> {
                val source = draft.source?.trim().orEmpty()
                if (source.startsWith("http://", ignoreCase = true) ||
                    source.startsWith("https://", ignoreCase = true)
                ) {
                    GetLineSubscriptionType.Url
                } else {
                    throw IllegalArgumentException(
                        "File profiles require Advanced BrowseFiles; " +
                            "headless import needs an http(s) source",
                    )
                }
            }
        }
    }

    override suspend fun requestConfigUpdate(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Unit> {
        // Same network budget as reimport: silent path runs ProfileProcessor inline.
        return callProfileBackend(op = "config_update", timeoutMs = REIMPORT_TIMEOUT_MS) {
            withProfile {
                val profile = queryByUUID(id.toUuid()) ?: return@withProfile
                // File profiles have no remote source; Url/External can be re-fetched.
                if (profile.imported && profile.type != Profile.Type.File) {
                    updateSilently(profile.uuid)
                }
            }
        }
    }

    override suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Unit> {
        return callProfileBackend(op = "delete_managed") {
            withProfile {
                val uuid = id.toUuid()
                // Missing is success — logout must still clear session.
                if (queryByUUID(uuid) != null) {
                    delete(uuid)
                }
                Unit
            }
        }
    }

    /**
     * [op] names the caller in the failure line. Callers that report the failure
     * themselves (import) pass [onUnavailable] instead, so the event is not
     * logged twice under two different names.
     */
    private suspend fun <T> callProfileBackend(
        op: String? = null,
        timeoutMs: Long = PROFILE_OPERATION_TIMEOUT_MS,
        onUnavailable: ((String) -> Unit)? = null,
        block: suspend () -> T,
    ): GetLineBackendResult<T> {
        fun unavailable(kind: String): GetLineBackendResult<Nothing> {
            onUnavailable?.invoke(kind)
            // Silent paths reported nothing at all: a failed subscription refresh
            // or managed delete left no trace in the shareable report.
            op?.let { Log.w("profile_backend op=$it outcome=unavailable kind=$kind") }
            return GetLineBackendResult.Unavailable
        }
        return try {
            GetLineBackendResult.Success(
                withTimeout(timeoutMs) {
                    block()
                }
            )
        } catch (_: TimeoutCancellationException) {
            unavailable("timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            unavailable(describeFailure(e))
        }
    }

    /**
     * A bare class name (`IllegalArgumentException`) does not say which value was
     * rejected, and the import path is the only report of a failed fetch we get.
     * Keep the message bounded and on one line; the diagnostic report redacts
     * URLs/tokens on top of this.
     */
    private fun describeFailure(e: Throwable): String {
        val kind = e::class.simpleName ?: "Exception"
        val cause = e.cause?.let { it::class.simpleName }
        val detail = e.message
            .orEmpty()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_FAILURE_DETAIL)
        return buildString {
            append(kind)
            if (cause != null) append(" cause=$cause")
            if (detail.isNotEmpty()) append(" msg=$detail")
        }
    }

    private fun Profile.toGetLineSummary(): GetLineSubscriptionSummary {
        return GetLineSubscriptionSummary(
            uuid = uuid.toString(),
            name = name,
            expire = expire,
            upload = upload,
            download = download,
            total = total,
        )
    }

    private fun GetLineSubscriptionType.toCmfaType(): Profile.Type {
        return when (this) {
            GetLineSubscriptionType.File -> Profile.Type.File
            GetLineSubscriptionType.Url -> Profile.Type.Url
        }
    }

    companion object {
        private const val PROFILE_OPERATION_TIMEOUT_MS = 8_000L
        /** Subscription fetch + commit can exceed local profile IPC latency. */
        private const val REIMPORT_TIMEOUT_MS = 60_000L
        /** Bounded non-cancellable orphan delete after failed re-provision. */
        private const val ORPHAN_CLEANUP_TIMEOUT_MS = 5_000L
        /** Exception message budget in the import failure line. */
        private const val MAX_FAILURE_DETAIL = 160
    }
}

private class CmfaGetLineVpnController(
    private val activity: Activity,
) : GetLineVpnController {
    override val running: Boolean
        get() = Remote.broadcasts.clashRunning

    override fun start(): Intent? {
        return activity.startClashService()
    }

    override fun stop() {
        activity.stopClashService()
    }

    override suspend fun querySession(): GetLineSession? {
        return runCatching {
            withClash {
                val durationMs = querySessionDurationMs()
                val packed = queryTrafficTotal()
                GetLineSession(
                    durationMs = durationMs,
                    traffic = unpackTraffic(packed),
                )
            }
        }.getOrNull()
    }
}

/**
 * Unpack CMFA packed Traffic (Long) into product byte counts.
 * scaleTraffic copied from core/util/Traffic.kt — decode only; format stays in design.
 */
private fun unpackTraffic(packed: Long): GetLineTraffic {
    return GetLineTraffic(
        uploadedBytes = scaleTraffic(packed ushr 32),
        downloadedBytes = scaleTraffic(packed and 0xFFFFFFFF),
    )
}

// Source: core/util/Traffic.kt private scaleTraffic — do not diverge.
private fun scaleTraffic(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val data = value and 0x3FFFFFFF

    return when (type) {
        0L -> data
        1L -> data * 1024
        2L -> data * 1024 * 1024
        3L -> data * 1024 * 1024 * 1024
        else -> throw IllegalArgumentException("invalid value type")
    }
}

private class CmfaGetLineNavigation(
    private val activity: Activity,
) : GetLineNavigation {
    override fun openAdvanced() {
        activity.startActivity(
            MainActivity::class.intent.putExtra(
                MainActivity.EXTRA_OPEN_ADVANCED,
                true,
            )
        )
    }

    override fun openOnboarding() {
        activity.startActivity(GetLineOnboardingActivity::class.intent)
        // Sign-in / logout leave Home; keep task clean so partial auth cannot
        // resurface Home underneath singleTask Onboarding.
        activity.finish()
    }

    override fun openLinkOnlySignIn() {
        activity.startActivity(
            GetLineOnboardingActivity::class.intent.putExtra(
                GetLineOnboardingActivity.EXTRA_LINK_ONLY_SIGN_IN,
                true,
            )
        )
        // No finish(): the link-only profile is still routing traffic, so Home stays
        // below and back / "Not now" returns to it. Home is singleTask, so a later
        // openHome() from Onboarding reuses that instance and drops this screen;
        // its onStart reconciles session vs UI (onSubscriptionHostResumed).
    }

    override fun openHome() {
        activity.startActivity(GetLineHomeActivity::class.intent)
    }
}

/** Maps CMFA [FetchStatus.Action] to product stages. Null = no user-facing copy. */
internal fun mapFetchActionToImportStage(action: FetchStatus.Action): GetLineImportStage? {
    return when (action) {
        FetchStatus.Action.FetchConfiguration,
        FetchStatus.Action.FetchProviders -> GetLineImportStage.LoadingConfig
        FetchStatus.Action.Verifying -> GetLineImportStage.Checking
        FetchStatus.Action.SubscriptionInfo -> null
    }
}

/** Compact diagnostic line for logs (raw CMFA stage + args). */
internal fun fetchStatusDiagnosticLine(status: FetchStatus): String {
    return "fetch action=${status.action} args=${status.args} " +
        "progress=${status.progress}/${status.max}"
}

private fun GetLineSubscriptionId.toUuid(): UUID {
    return UUID.fromString(value)
}
