package pro.getline.vpn.getline

import android.content.Intent

interface GetLineBackend {
    val subscriptions: GetLineSubscriptionRepository
    val vpn: GetLineVpnController
    val navigation: GetLineNavigation
}

interface GetLineSubscriptionRepository {
    suspend fun snapshot(): GetLineBackendResult<GetLineSubscriptionSnapshot>
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
     * Force re-fetch of an imported URL profile config (nodes + userinfo) in-process.
     * Used after /api/subscriptions shows active so Servers pick up provider node changes.
     *
     * Silent: no ProfileWorker result notifications / Properties deep-link.
     * Success still emits ProfileChanged for Servers/Home reload.
     * No-op success when the UUID is missing, not imported, or is a File profile.
     */
    suspend fun requestConfigUpdate(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Unit>

    /**
     * Delete only the GetLine-managed profile by UUID.
     * Does not touch other imported/manual profiles or app settings.
     * No-op success when the UUID is already gone.
     */
    suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Unit>
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
}

interface GetLineVpnController {
    val running: Boolean

    fun start(): Intent?
    fun stop()
}

interface GetLineNavigation {
    fun editSubscription(id: GetLineSubscriptionId): Intent
    fun classifyImportResult(resultCode: Int, data: Intent?): GetLineImportResult
    fun openServerSelection()
    fun openProfiles()
    fun openAdvanced()
    fun openOnboarding()
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
    val name: String,
    val expire: Long,
    val upload: Long,
    val download: Long,
    val total: Long,
)

data class GetLineSubscriptionSnapshot(
    val active: GetLineSubscriptionSummary?,
    val hasImported: Boolean,
)

sealed class GetLineImportResult {
    /**
     * @param source committed profile URL when known (PropertiesActivity manual entry
     *   or auto-commit draft). Required for repair binding on URL import.
     */
    data class Confirmed(
        val source: String? = null,
        val name: String? = null,
    ) : GetLineImportResult()

    data class Failed(val offline: Boolean) : GetLineImportResult()
    object Cancelled : GetLineImportResult()
}
