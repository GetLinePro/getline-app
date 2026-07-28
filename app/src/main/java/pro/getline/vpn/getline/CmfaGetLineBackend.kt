package pro.getline.vpn.getline

import android.app.Activity
import android.content.Intent
import pro.getline.vpn.GetLineHomeActivity
import pro.getline.vpn.GetLineOnboardingActivity
import pro.getline.vpn.MainActivity
import pro.getline.vpn.ProfilesActivity
import pro.getline.vpn.PropertiesActivity
import pro.getline.vpn.ProxyActivity
import pro.getline.vpn.common.util.intent
import pro.getline.vpn.common.util.setUUID
import pro.getline.vpn.remote.Remote
import pro.getline.vpn.service.model.Profile
import pro.getline.vpn.util.startClashService
import pro.getline.vpn.util.stopClashService
import pro.getline.vpn.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID

object GetLineBackendProvider {
    fun create(activity: Activity): GetLineBackend {
        return CmfaGetLineBackend(activity)
    }
}

private class CmfaGetLineBackend(
    activity: Activity,
) : GetLineBackend {
    override val subscriptions: GetLineSubscriptionRepository =
        CmfaGetLineSubscriptionRepository()
    override val vpn: GetLineVpnController = CmfaGetLineVpnController(activity)
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
        return callProfileBackend(timeoutMs = REIMPORT_TIMEOUT_MS) {
            withProfile {
                val existingUuid = managedId
                    ?.toUuid()
                    ?.let { uuid -> queryByUUID(uuid)?.uuid }
                // Only delete on failure when we minted a brand-new UUID this call.
                var createdOrphan: UUID? = null
                val uuid = existingUuid ?: create(draft.type.toCmfaType(), draft.name).also {
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
                    commit(uuid)
                    val imported = queryByUUID(uuid)?.takeIf { it.imported }
                        ?: throw IllegalStateException("profile not imported after commit")
                    setActive(imported)
                    createdOrphan = null
                    GetLineSubscriptionId(uuid.toString())
                } catch (e: Throwable) {
                    val orphan = createdOrphan
                    if (orphan != null) {
                        // Parent may already be cancelled (withTimeout). Cleanup must not
                        // ride that cancellation or Binder delete is dropped silently.
                        withContext(NonCancellable) {
                            runCatching {
                                withTimeout(ORPHAN_CLEANUP_TIMEOUT_MS) {
                                    delete(orphan)
                                }
                            }
                        }
                    }
                    throw e
                }
            }
        }
    }

    override suspend fun requestConfigUpdate(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Unit> {
        // Same network budget as reimport: silent path runs ProfileProcessor inline.
        return callProfileBackend(timeoutMs = REIMPORT_TIMEOUT_MS) {
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
        return callProfileBackend {
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

    private suspend fun <T> callProfileBackend(
        timeoutMs: Long = PROFILE_OPERATION_TIMEOUT_MS,
        block: suspend () -> T,
    ): GetLineBackendResult<T> {
        return try {
            GetLineBackendResult.Success(
                withTimeout(timeoutMs) {
                    block()
                }
            )
        } catch (_: TimeoutCancellationException) {
            GetLineBackendResult.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            GetLineBackendResult.Unavailable
        }
    }

    private fun Profile.toGetLineSummary(): GetLineSubscriptionSummary {
        return GetLineSubscriptionSummary(
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
}

private class CmfaGetLineNavigation(
    private val activity: Activity,
) : GetLineNavigation {
    override fun editSubscription(id: GetLineSubscriptionId): Intent {
        return PropertiesActivity::class.intent
            .setUUID(id.toUuid())
            .putExtra(PropertiesActivity.EXTRA_GET_LINE_IMPORT, true)
    }

    override fun classifyImportResult(
        resultCode: Int,
        data: Intent?,
    ): GetLineImportResult {
        return when (resultCode) {
            Activity.RESULT_OK -> GetLineImportResult.Confirmed(
                source = data?.getStringExtra(PropertiesActivity.EXTRA_GET_LINE_COMMITTED_SOURCE)
                    ?.takeIf { it.isNotBlank() },
                name = data?.getStringExtra(PropertiesActivity.EXTRA_GET_LINE_COMMITTED_NAME)
                    ?.takeIf { it.isNotBlank() },
            )
            PropertiesActivity.RESULT_GET_LINE_IMPORT_FAILED ->
                GetLineImportResult.Failed(
                    offline = data?.getBooleanExtra(
                        PropertiesActivity.EXTRA_GET_LINE_IMPORT_OFFLINE,
                        false,
                    ) == true,
                )
            else -> GetLineImportResult.Cancelled
        }
    }

    override fun openServerSelection() {
        activity.startActivity(ProxyActivity::class.intent)
    }

    override fun openProfiles() {
        activity.startActivity(ProfilesActivity::class.intent)
    }

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
    }

    override fun openHome() {
        activity.startActivity(GetLineHomeActivity::class.intent)
    }
}

private fun GetLineSubscriptionId.toUuid(): UUID {
    return UUID.fromString(value)
}
