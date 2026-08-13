package pro.getline.vpn.cmfa

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import pro.getline.vpn.GetLineHomeActivity
import pro.getline.vpn.GetLineOnboardingActivity
import pro.getline.vpn.cmfa.servers.CmfaVpnServerSelectionRepository
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.core.model.FetchStatus
import pro.getline.vpn.getlineui.model.GetLineImportStage
import pro.getline.vpn.getlineui.model.GetLineTraffic
import pro.getline.vpn.getline.ConfigUpdateResult
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
import pro.getline.vpn.getline.ImportedProfileIntegrity
import pro.getline.vpn.getline.LocalActiveRepair
import pro.getline.vpn.getline.LocalActiveRepairDecision
import pro.getline.vpn.getline.ManagedProfileDeleteOutcome
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withClash
import com.github.kr328.clash.util.BinderDiedException
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.util.withProfile
import com.github.kr328.clash.util.withProfileOnce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

class CmfaGetLineBackend(
    activity: Activity,
) : GetLineBackend {
    override val subscriptions: GetLineSubscriptionRepository =
        CmfaGetLineSubscriptionRepository(
            importedRoot = activity.applicationContext.importedDir,
        )
    override val vpn: GetLineVpnController = CmfaGetLineVpnController(activity)
    override val servers: VpnServerSelectionRepository =
        CmfaVpnServerSelectionRepository(
            context = activity,
            importedRoot = activity.applicationContext.importedDir,
            vpnRunning = { vpn.running },
        )
    override val navigation: GetLineNavigation = CmfaGetLineNavigation(activity)
}

private class CmfaGetLineSubscriptionRepository(
    private val importedRoot: File,
) : GetLineSubscriptionRepository {
    override suspend fun snapshot(): GetLineBackendResult<GetLineSubscriptionSnapshot> {
        return callProfileBackend(op = "snapshot") {
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
        return callProfileBackend(op = "has_imported") {
            withProfile {
                queryAll().any { it.imported }
            }
        }
    }

    override suspend fun hasActiveImported(): GetLineBackendResult<Boolean> {
        return callProfileBackend(op = "has_active_imported") {
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
        return callProfileBackend(op = "create_or_update_pending") {
            withProfile {
                val uuid = resolveReusableImportUuid(
                    reuseId = reuseId,
                    type = draft.type.toCmfaType(),
                    name = draft.name,
                    importedRoot = importedRoot,
                ).first
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
        return callProfileBackend(op = "activate") {
            withProfile {
                queryByUUID(id.toUuid())
                    ?.takeIf { it.imported }
                    ?.takeIf { profile ->
                        ImportedProfileIntegrity.inspect(
                            importedRoot.resolve(profile.uuid.toString()),
                        ) == ImportedProfileIntegrity.Verdict.Intact
                    }
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
        return callProfileBackend(op = "repair_local_active") {
            withProfile {
                val managed = managedUuid?.takeIf { it.isNotBlank() }
                val imported = queryAll().filter { it.imported }
                val active = queryActive()?.takeIf { it.imported }
                val decided = LocalActiveRepairDecision.decide(
                    managedUuid = managed,
                    importedUuids = imported.map { it.uuid.toString() },
                    activeUuid = active?.uuid?.toString(),
                    profileDirectory = { importedRoot.resolve(it) },
                    debounceMs = INTEGRITY_DEBOUNCE_MS,
                )
                val result = if (decided is LocalActiveRepair.Ready &&
                    active?.uuid?.toString() != decided.activeUuid
                ) {
                    val profile = queryByUUID(UUID.fromString(decided.activeUuid))
                        ?.takeIf { it.imported }
                    if (profile == null) {
                        LocalActiveRepair.ManagedAbsent(
                            managedUuid = managed,
                            managedIsImported = imported.any {
                                it.uuid.toString() == decided.activeUuid
                            },
                        )
                    } else {
                        setActive(profile)
                        decided
                    }
                } else {
                    decided
                }
                logProfileIntegrity(result)
                result
            }
        }
    }

    override suspend fun reimportAndActivate(
        draft: GetLineSubscriptionDraft,
        managedId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        // activate=true keeps orphan cleanup across setActive (same try block as commit).
        return try {
            callProfileBackend(op = "reimport", timeoutMs = REIMPORT_TIMEOUT_MS) {
                withProfileOnce {
                    importPending(
                        draft = draft,
                        reuseId = managedId,
                        onProgress = {},
                        activate = true,
                        diagnosticOp = null,
                        importedRoot = importedRoot,
                    )
                }
            }
        } catch (_: BinderDiedException) {
            // Repair has no pending-import journal; keep its existing Unavailable contract.
            GetLineBackendResult.Unavailable
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

        val result = try {
            callProfileBackend(
                timeoutMs = REIMPORT_TIMEOUT_MS,
                onUnavailable = { kind -> unavailableKind = kind },
            ) {
                withProfileOnce {
                    Log.i("profile_import stage=remote_acquired op=$op")
                    importPending(
                        draft = draft,
                        reuseId = reuseId,
                        onProgress = onProgress,
                        activate = false,
                        diagnosticOp = op,
                        importedRoot = importedRoot,
                    )
                }
            }
        } catch (e: BinderDiedException) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            Log.w(
                "profile_import end op=$op outcome=unavailable " +
                    "elapsed_ms=$elapsedMs kind=binder_died",
            )
            throw e
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

    override suspend fun requestConfigUpdate(
        id: GetLineSubscriptionId,
    ): ConfigUpdateResult {
        return runConfigUpdate {
            withProfile {
                updateManagedProfileConfig(id)
            }
        }
    }

    override suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<ManagedProfileDeleteOutcome> {
        return callProfileBackend(op = "delete_managed") {
            withProfile {
                deleteManagedProfile(id)
            }
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
}

private fun logProfileIntegrity(repair: LocalActiveRepair) {
    val verdict: String
    val detail: String
    when (repair) {
        is LocalActiveRepair.Ready -> {
            verdict = "ok"
            detail = "na"
        }
        is LocalActiveRepair.ManagedAbsent -> {
            verdict = "absent"
            detail = "na"
        }
        is LocalActiveRepair.ManagedCorrupt -> {
            verdict = "corrupt"
            detail = repair.detail
        }
    }
    Log.i("profile_integrity verdict=$verdict detail=$detail")
}

private const val PROFILE_OPERATION_TIMEOUT_MS = 8_000L
/** Best-effort debounce before treating a missing/empty dir as corrupt. */
private const val INTEGRITY_DEBOUNCE_MS = 80L
/** Subscription fetch + commit can exceed local profile IPC latency. */
private const val REIMPORT_TIMEOUT_MS = 60_000L
/** Bounded non-cancellable orphan delete after failed re-provision. */
private const val ORPHAN_CLEANUP_TIMEOUT_MS = 5_000L
/** Exception message budget in the import failure line. */
private const val MAX_FAILURE_DETAIL = 160

/**
 * Timeout/failure envelope around one profile-backend call.
 *
 * [op] names the caller in the failure line. Callers that report the failure
 * themselves (import) pass [onUnavailable] instead, so the event is not
 * logged twice under two different names.
 *
 * Top-level and `internal` for the unit test: the Binder lives inside [block],
 * so the envelope itself needs no `IProfileManager` to exercise.
 */
internal suspend fun <T> callProfileBackend(
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
    } catch (e: BinderDiedException) {
        throw e
    } catch (e: Exception) {
        unavailable(describeFailure(e))
    }
}

/** Same network budget as reimport: the silent path runs ProfileProcessor inline. */
internal suspend fun runConfigUpdate(
    block: suspend () -> ConfigUpdateResult,
): ConfigUpdateResult {
    return when (
        val result = callProfileBackend(
            op = "config_update",
            timeoutMs = REIMPORT_TIMEOUT_MS,
            block = block,
        )
    ) {
        GetLineBackendResult.Unavailable -> ConfigUpdateResult.Unavailable
        is GetLineBackendResult.Success -> result.value.also { outcome ->
            if (outcome == ConfigUpdateResult.NotFound) {
                Log.w("profile_backend op=config_update outcome=not_found")
            }
        }
    }
}

/**
 * A bare class name (`IllegalArgumentException`) does not say which value was
 * rejected, and the import path is the only report of a failed fetch we get.
 * Keep the message bounded and on one line; the diagnostic report redacts
 * URLs/tokens on top of this.
 */
internal fun describeFailure(e: Throwable): String {
    val kind = e::class.simpleName ?: "Exception"
    val cause = e.reportableCause()?.let { it::class.simpleName }
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

/** Delete exactly [id], keeping an already-absent profile observable. */
internal suspend fun IProfileManager.deleteManagedProfile(
    id: GetLineSubscriptionId,
): ManagedProfileDeleteOutcome {
    val uuid = id.toUuid()
    if (queryByUUID(uuid) == null) {
        return ManagedProfileDeleteOutcome.NotFound
    }
    delete(uuid)
    return ManagedProfileDeleteOutcome.Deleted
}

/** Refresh exactly [id], keeping missing and non-refreshable rows observable. */
internal suspend fun IProfileManager.updateManagedProfileConfig(
    id: GetLineSubscriptionId,
): ConfigUpdateResult {
    val profile = queryByUUID(id.toUuid()) ?: return ConfigUpdateResult.NotFound
    // File rows have no remote source; pending rows have no committed config to update.
    if (!profile.imported || profile.type == Profile.Type.File) {
        return ConfigUpdateResult.NotRefreshable
    }
    updateSilently(profile.uuid)
    return ConfigUpdateResult.Updated
}

/**
 * The cause worth printing, skipping coroutine stacktrace-recovery copies.
 *
 * When an exception crosses `withTimeout`/`withContext`, kotlinx rethrows a
 * *copy* carrying the original as its cause. A plain `cause` therefore reports
 * the exception's own class back at us and hides the real chain — exactly on
 * the import path the cause was added for.
 */
private fun Throwable.reportableCause(): Throwable? {
    var candidate = cause
    while (
        candidate != null &&
        candidate::class == this::class &&
        candidate.message == message
    ) {
        candidate = candidate.cause
    }
    return candidate
}

/**
 * Reuse [reuseId] when the row still exists and, if imported, its directory
 * is present. A DAO row with a missing imported directory cannot be patched:
 * [IProfileManager.patch] clones that directory into pending and throws.
 *
 * @return uuid to continue with, and a non-null orphan only when this call
 * minted a new row (so failure cleanup will not delete a pre-existing profile).
 */
internal suspend fun IProfileManager.resolveReusableImportUuid(
    reuseId: GetLineSubscriptionId?,
    type: Profile.Type,
    name: String,
    importedRoot: File?,
): Pair<UUID, UUID?> {
    val existing = reuseId?.toUuid()?.let { queryByUUID(it) }
    if (existing != null) {
        val importedDirGone = existing.imported &&
            importedRoot != null &&
            ImportedProfileIntegrity.inspect(
                importedRoot.resolve(existing.uuid.toString()),
            ) == ImportedProfileIntegrity.Verdict.MissingDirectory
        if (!importedDirGone) {
            return existing.uuid to null
        }
        delete(existing.uuid)
    }
    val created = create(type, name)
    return created to created
}

/**
 * Shared headless import. [createdOrphan] is cleared only after the full path
 * succeeds — including [setActive] when [activate] is true — so a failed
 * activation still deletes a UUID minted by this call.
 */
internal suspend fun IProfileManager.importPending(
    draft: GetLineSubscriptionDraft,
    reuseId: GetLineSubscriptionId?,
    onProgress: suspend (GetLineImportStage) -> Unit,
    activate: Boolean,
    diagnosticOp: String?,
    importedRoot: File? = null,
): GetLineSubscriptionId {
    // File profiles ship an empty config.yaml; ProfileProcessor uses force=false
    // so commit never fetches draft.source and always fails. Advanced BrowseFiles
    // is the supported File path. ExternalControl sometimes tags a remote URL as
    // type=file — promote those to Url before create. Non-URL File: refuse early.
    val importType = resolveHeadlessImportType(draft)

    val (uuid, createdOrphanSeed) = resolveReusableImportUuid(
        reuseId = reuseId,
        type = importType.toCmfaType(),
        name = draft.name,
        importedRoot = importedRoot,
    )
    // Only delete on failure when we minted a brand-new UUID this call.
    var createdOrphan: UUID? = createdOrphanSeed
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
                    "reused=${if (createdOrphanSeed == null) 1 else 0}",
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

private fun GetLineSubscriptionType.toCmfaType(): Profile.Type {
    return when (this) {
        GetLineSubscriptionType.File -> Profile.Type.File
        GetLineSubscriptionType.Url -> Profile.Type.Url
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
