package pro.getline.vpn.getline

import android.app.Activity
import android.content.Intent
import pro.getline.vpn.cmfa.CmfaGetLineBackend
import pro.getline.vpn.getlineui.model.GetLineImportStage
import pro.getline.vpn.getlineui.model.GetLineTraffic
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository

interface GetLineBackend {
    val subscriptions: GetLineSubscriptionRepository
    val vpn: GetLineVpnController
    val servers: VpnServerSelectionRepository
    val navigation: GetLineNavigation
}

object GetLineBackendProvider {
    fun create(activity: Activity): GetLineBackend {
        return CmfaGetLineBackend(activity)
    }
}

interface GetLineSubscriptionRepository {
    suspend fun snapshot(): GetLineBackendResult<GetLineSubscriptionSnapshot>

    /** Imported profile by stable binding, regardless of which profile is active. */
    suspend fun findImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<GetLineSubscriptionSummary?>

    suspend fun hasImported(): GetLineBackendResult<Boolean>
    suspend fun hasActiveImported(): GetLineBackendResult<Boolean>
    suspend fun createPending(
        draft: GetLineSubscriptionDraft,
    ): GetLineBackendResult<GetLineSubscriptionId>

    /**
     * Creates a pending URL/file profile, or reuses [reuseId] when it still exists
     * so repeated Telegram login does not accumulate duplicate profiles.
     */
    suspend fun createOrUpdatePending(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId? = null,
    ): GetLineBackendResult<GetLineSubscriptionId>

    suspend fun activateIfImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Boolean>

    /**
     * Local heal only: restore ServiceStore active to [managedUuid] when that
     * profile is still imported. Never activates an unrelated imported profile.
     *
     * Does not network re-import. See [reimportAndActivate] for that path.
     *
     * @return true when an active imported profile is present after the call
     */
    suspend fun ensureActiveImported(
        managedUuid: String? = null,
    ): GetLineBackendResult<Boolean>

    /**
     * Local inventory + optional setActive for the managed binding.
     * Used by the repair ladder to prove whether remote re-provision is needed.
     */
    suspend fun repairLocalActive(
        managedUuid: String? = null,
    ): GetLineBackendResult<LocalActiveRepair>

    /**
     * Network repair: create/update pending from [draft], commit (fetch), activate.
     * Reuses [managedId] when that UUID still exists so the GetLine binding stays stable.
     *
     * Does not open profile UI. Caller persists the returned id as managed binding.
     */
    suspend fun reimportAndActivate(
        draft: GetLineSubscriptionDraft,
        managedId: GetLineSubscriptionId? = null,
    ): GetLineBackendResult<GetLineSubscriptionId>

    /**
     * Create-or-reuse pending profile, patch from [draft], fetch and commit.
     * Does not activate — caller decides (see [activateIfImported]).
     * [onProgress] is best-effort UI feedback; drops intermediate stages under load.
     */
    suspend fun importAndCommit(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId? = null,
        onProgress: suspend (GetLineImportStage) -> Unit = {},
    ): GetLineBackendResult<GetLineSubscriptionId>

    /**
     * Force re-fetch of an imported URL profile config (nodes + userinfo) in-process.
     * Used after /api/subscriptions shows active so Servers pick up provider node changes.
     *
     * Silent: no progress notification / Properties deep-link.
     * [ConfigUpdateResult.Updated] still emits ProfileChanged for Servers/Home reload.
     * Missing and non-refreshable profiles remain distinguishable from an update.
     */
    suspend fun requestConfigUpdate(
        id: GetLineSubscriptionId,
    ): ConfigUpdateResult

    /**
     * Delete only the GetLine-managed profile by UUID.
     * Does not touch other imported/manual profiles or app settings.
     * Missing is a completed, idempotent cleanup, but remains distinguishable
     * from a delete that removed a profile.
     */
    suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<ManagedProfileDeleteOutcome>
}

sealed class ManagedProfileDeleteOutcome {
    object Deleted : ManagedProfileDeleteOutcome()
    object NotFound : ManagedProfileDeleteOutcome()
}

sealed class ConfigUpdateResult {
    object Updated : ConfigUpdateResult()
    object NotFound : ConfigUpdateResult()
    /** The row exists but cannot be remotely refreshed (File or pending). */
    object NotRefreshable : ConfigUpdateResult()
    object Unavailable : ConfigUpdateResult()
}

/**
 * Result of a local-only active-profile repair pass.
 */
sealed class LocalActiveRepair {
    /** An imported profile is already active (or was just activated). */
    data class Ready(val activeUuid: String) : LocalActiveRepair()

    /**
     * No valid active selection; managed UUID is missing or not imported.
     * Remote re-provision may be appropriate when a session or saved URL exists.
     */
    data class ManagedAbsent(
        val managedUuid: String?,
        val managedIsImported: Boolean,
    ) : LocalActiveRepair()

    /**
     * DAO row exists but the profile directory is missing or incomplete.
     * Local setActive cannot heal this; re-import may.
     */
    data class ManagedCorrupt(
        val managedUuid: String?,
        val detail: String,
    ) : LocalActiveRepair()
}

interface GetLineVpnController {
    val running: Boolean

    fun start(): Intent?
    fun stop()

    /** null when the tunnel is not running or the query failed. */
    suspend fun querySession(): GetLineSession?
}

data class GetLineSession(
    val durationMs: Long?,
    val traffic: GetLineTraffic,
)

interface GetLineNavigation {
    fun openOnboarding()
    /**
     * Sign-in offered on top of a working link-only profile. Unlike [openOnboarding]
     * the caller is kept alive: the VPN keeps running, so back must land on Home
     * instead of leaving the app.
     */
    fun openLinkOnlySignIn()
    fun openHome()
}

sealed class GetLineBackendResult<out T> {
    data class Success<T>(val value: T) : GetLineBackendResult<T>()
    object Unavailable : GetLineBackendResult<Nothing>()
}

enum class GetLineSubscriptionType {
    File,
    Url,
}

@JvmInline
value class GetLineSubscriptionId(
    val value: String,
)

data class GetLineSubscriptionDraft(
    val type: GetLineSubscriptionType,
    val name: String,
    val source: String? = null,
    val interval: Long = 0L,
)

data class GetLineSubscriptionSummary(
    val uuid: String,
    val name: String,
    val expire: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
    val tag: String? = null,
    val status: String? = null,
    val deviceLimit: Int? = null,
)

data class GetLineSubscriptionSnapshot(
    val active: GetLineSubscriptionSummary?,
    val hasImported: Boolean,
)
