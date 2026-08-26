package pro.getline.vpn.cmfa

import android.content.Context
import android.content.ServiceConnection
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.model.ProfileSelectionSnapshot
import com.github.kr328.clash.service.model.ProfileStateSnapshot
import com.github.kr328.clash.service.model.ProfileStorageHealth
import com.github.kr328.clash.service.model.ProfileStorageSnapshot
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.service.remote.wrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.github.kr328.clash.util.BinderDiedException
import com.github.kr328.clash.util.runProfileRemoteBlock
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.ManagedProfileDeleteOutcome
import pro.getline.vpn.getlineui.model.GetLineImportStage
import java.io.IOException
import java.util.UUID

/**
 * The import is a transaction the profile backend does not provide: create,
 * patch, commit, verify, optionally activate — and on any failure delete the
 * profile row this call minted, but never one that already existed.
 *
 * Getting the second half wrong is invisible in the UI and permanent: either a
 * dead half-imported profile accumulates in the list on every retry, or a retry
 * over an existing subscription deletes the profile that is currently routing
 * traffic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportTransactionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val draft = GetLineSubscriptionDraft(
        type = GetLineSubscriptionType.Url,
        name = "GetLine",
        source = "https://example.invalid/sub",
        interval = 0L,
    )

    @Test
    fun configUpdate_importedUrl_reportsUpdated() = runBlocking {
        val uuid = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(uuid, imported = true)

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(uuid.toString()),
            bindService = bindTo(backend),
        )

        assertEquals(ConfigUpdateResult.Updated, outcome)
        assertEquals(listOf(uuid), backend.updated)
    }

    @Test
    fun configUpdate_missingProfile_reportsNotFound() = runBlocking {
        val backend = FakeProfileManager()

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(UUID.randomUUID().toString()),
            bindService = bindTo(backend),
        )

        assertEquals(ConfigUpdateResult.NotFound, outcome)
        assertTrue(backend.updated.isEmpty())
    }

    @Test
    fun configUpdate_fileProfile_reportsNotRefreshable() = runBlocking {
        val uuid = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(uuid, imported = true, type = Profile.Type.File)

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(uuid.toString()),
            bindService = bindTo(backend),
        )

        assertEquals(ConfigUpdateResult.NotRefreshable, outcome)
        assertTrue(backend.updated.isEmpty())
    }

    @Test
    fun configUpdate_pendingProfile_reportsNotRefreshable() = runBlocking {
        val uuid = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(uuid, imported = false)

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(uuid.toString()),
            bindService = bindTo(backend),
        )

        assertEquals(ConfigUpdateResult.NotRefreshable, outcome)
        assertTrue(backend.updated.isEmpty())
    }

    @Test
    fun configUpdate_bindException_reportsUnavailable() = runBlocking {
        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(UUID.randomUUID().toString()),
            bindService = { _, _ -> throw IOException("bind failed") },
        )

        assertEquals(ConfigUpdateResult.Unavailable, outcome)
    }

    @Test
    fun configUpdate_bindRejected_reportsUnavailable_andUnbinds() = runBlocking {
        var unbinds = 0

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(UUID.randomUUID().toString()),
            bindService = { _, _ -> false },
            unbindService = { _, _ -> unbinds++ },
        )

        assertEquals(ConfigUpdateResult.Unavailable, outcome)
        assertEquals(1, unbinds)
    }

    @Test
    fun configUpdate_timeout_reportsUnavailable_andUnbinds() = runBlocking {
        var unbinds = 0

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(UUID.randomUUID().toString()),
            timeoutMs = 100,
            bindService = { _, _ -> true },
            unbindService = { _, _ -> unbinds++ },
        )

        assertEquals(ConfigUpdateResult.Unavailable, outcome)
        assertEquals(1, unbinds)
    }

    @Test
    fun configUpdate_disconnect_reportsUnavailable_andUnbinds() = runBlocking {
        var unbinds = 0

        val outcome = updateImportedProfileSilently(
            context = RuntimeEnvironment.getApplication(),
            id = GetLineSubscriptionId(UUID.randomUUID().toString()),
            bindService = { _, connection ->
                connection.onServiceDisconnected(null)
                true
            },
            unbindService = { _, _ -> unbinds++ },
        )

        assertEquals(ConfigUpdateResult.Unavailable, outcome)
        assertEquals(1, unbinds)
    }

    @Test
    fun configUpdate_cancellation_rethrows_andUnbinds() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var unbinds = 0
        var cancelled = false

        coroutineScope {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    updateImportedProfileSilently(
                        context = RuntimeEnvironment.getApplication(),
                        id = GetLineSubscriptionId(UUID.randomUUID().toString()),
                        timeoutMs = 10_000,
                        bindService = { _, _ ->
                            entered.complete(Unit)
                            true
                        },
                        unbindService = { _, _ -> unbinds++ },
                    )
                    fail("cancellation must not become a terminal result")
                } catch (_: CancellationException) {
                    cancelled = true
                }
            }
            entered.await()
            job.cancelAndJoin()
        }

        assertTrue(cancelled)
        assertEquals(1, unbinds)
    }

    private fun bindTo(profileManager: IProfileManager): (Context, ServiceConnection) -> Boolean =
        { _, connection ->
            val remote = object : IRemoteService {
                override fun clash() = error("not used")

                override fun profile(): IProfileManager = profileManager
            }
            connection.onServiceConnected(null, remote.wrap())
            true
        }

    @Test
    fun managedDelete_existingProfile_reportsDeleted() = runBlocking {
        val uuid = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(uuid, imported = true)

        val outcome = backend.deleteManagedProfile(GetLineSubscriptionId(uuid.toString()))

        assertEquals(ManagedProfileDeleteOutcome.Deleted, outcome)
        assertEquals(listOf(uuid), backend.deleted)
    }

    @Test
    fun managedDelete_missingProfile_reportsNotFound() = runBlocking {
        val backend = FakeProfileManager()

        val outcome = backend.deleteManagedProfile(
            GetLineSubscriptionId(UUID.randomUUID().toString()),
        )

        assertEquals(ManagedProfileDeleteOutcome.NotFound, outcome)
        assertTrue(backend.deleted.isEmpty())
    }

    @Test
    fun success_returnsTheNewId_andDeletesNothing() = runBlocking {
        val backend = FakeProfileManager()

        val id = backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)

        val created = backend.created.single()
        assertEquals(GetLineSubscriptionId(created.toString()), id)
        assertEquals(listOf(created), backend.patched)
        assertTrue("a successful import must not delete anything", backend.deleted.isEmpty())
        assertTrue("activate=false must not touch the active profile", backend.activated.isEmpty())
    }

    @Test
    fun commitFailure_deletesTheUuidThisCallMinted_exactlyOnce() = runBlocking {
        val backend = FakeProfileManager(onCommit = { _, _ -> throw IOException("EOF") })

        assertFails<IOException> {
            backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
        }

        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
    }

    /**
     * A retry over the managed subscription reuses its UUID. Deleting on failure
     * here would remove the profile that is still routing traffic — the failure
     * is a fetch that did not land, not a reason to drop the subscription.
     */
    @Test
    fun commitFailure_onAReusedProfile_deletesNothing() = runBlocking {
        val existing = UUID.randomUUID()
        val backend = FakeProfileManager(onCommit = { _, _ -> throw IOException("EOF") })
        backend.seed(existing, imported = true)

        assertFails<IOException> {
            backend.importPending(
                draft,
                GetLineSubscriptionId(existing.toString()),
                {},
                activate = false,
                diagnosticOp = null,
            )
        }

        assertTrue("reused profiles are not this call's to delete", backend.deleted.isEmpty())
        assertTrue(backend.created.isEmpty())
    }

    /**
     * A stale UUID in the session store is not a reused profile: nothing was
     * found, so this call minted the row and owns its cleanup.
     */
    @Test
    fun commitFailure_afterAStaleReuseId_deletesTheReplacementItCreated() = runBlocking {
        val backend = FakeProfileManager(onCommit = { _, _ -> throw IOException("EOF") })

        assertFails<IOException> {
            backend.importPending(
                draft,
                GetLineSubscriptionId(UUID.randomUUID().toString()),
                {},
                activate = false,
                diagnosticOp = null,
            )
        }

        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
    }

    /**
     * `commit` returning is not proof of an import: a fetch that produced no
     * config leaves `imported=false`. Reporting success there hands the caller a
     * profile that cannot start a VPN.
     */
    @Test
    fun commitReturnsWithoutImporting_failsAndCleansUp() = runBlocking {
        val backend = FakeProfileManager(onCommit = { _, _ -> /* no import happened */ })

        val error = assertFails<IllegalStateException> {
            backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
        }

        assertEquals("profile not imported after commit", error.message)
        assertEquals(backend.created, backend.deleted)
    }

    /**
     * `setActive` is inside the transaction on purpose. A profile that imported
     * but could not be activated is not the outcome the caller asked for, and
     * leaving it behind means the next retry finds a stranger in the list.
     */
    @Test
    fun failedActivation_stillDeletesTheProfileThisCallCreated() = runBlocking {
        val backend = FakeProfileManager(onSetActive = { throw IOException("binder died") })

        assertFails<IOException> {
            backend.importPending(draft, null, {}, activate = true, diagnosticOp = null)
        }

        assertEquals(backend.created, backend.deleted)
    }

    @Test
    fun failedPrimaryActivation_keepsTheOldActiveProfile() = runBlocking {
        val oldActive = UUID.randomUUID()
        val backend = FakeProfileManager(
            onSetActive = { profile ->
                if (profile.uuid != oldActive) throw IOException("activation rejected")
            },
        )
        backend.seed(oldActive, imported = true)
        backend.setActive(backend.queryByUUID(oldActive)!!)

        assertFails<IOException> {
            backend.importPending(draft, null, {}, activate = true, diagnosticOp = null)
        }

        assertEquals(listOf(oldActive), backend.activated)
        assertEquals(oldActive, backend.queryActive()?.uuid)
        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
    }

    @Test
    fun failedRepairActivation_keepsTheReusedProfile() = runBlocking {
        val managed = UUID.randomUUID()
        val backend = FakeProfileManager(onSetActive = { throw IOException("activation rejected") })
        backend.seed(managed, imported = true)

        assertFails<IOException> {
            backend.importPending(
                draft,
                GetLineSubscriptionId(managed.toString()),
                {},
                activate = true,
                diagnosticOp = null,
            )
        }

        assertTrue(backend.created.isEmpty())
        assertTrue(backend.deleted.isEmpty())
        assertEquals(managed, backend.queryByUUID(managed)?.uuid)
    }

    /**
     * The screen closing or the 60 s envelope firing cancels the caller mid-commit.
     * Cleanup runs under `NonCancellable`; without it the delete is dropped
     * silently and the orphan survives exactly the failure it exists for.
     */
    @Test
    fun cancellationMidCommit_stillDeletesTheOrphan() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val backend = FakeProfileManager(
            onCommit = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
        )

        coroutineScope {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
            }
            entered.await()
            job.cancelAndJoin()
        }

        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
    }

    /**
     * File profiles ship an empty config.yaml and commit never fetches the source,
     * so a headless import of one always fails. Refusing before `create` is what
     * keeps that failure from leaving a row behind.
     */
    @Test
    fun fileDraftWithoutAnHttpSource_isRefusedBeforeCreatingAnything() = runBlocking {
        val backend = FakeProfileManager()

        assertFails<IllegalArgumentException> {
            backend.importPending(
                draft.copy(type = GetLineSubscriptionType.File, source = "/sdcard/config.yaml"),
                null,
                {},
                activate = false,
                diagnosticOp = null,
            )
        }

        assertTrue(backend.created.isEmpty())
        assertTrue(backend.deleted.isEmpty())
    }

    /** ExternalControl tags remote URLs as `type=file`; those are importable as Url. */
    @Test
    fun fileDraftWithAnHttpsSource_isCreatedAsUrl() = runBlocking {
        val backend = FakeProfileManager()

        backend.importPending(
            draft.copy(type = GetLineSubscriptionType.File),
            null,
            {},
            activate = false,
            diagnosticOp = null,
        )

        assertEquals(Profile.Type.Url, backend.createdType)
    }

    /**
     * Progress is drained inside the import's own scope, so the last stage is
     * delivered before the call returns rather than racing the screen that is
     * about to be told the import finished.
     */
    @Test
    fun progressReachesTheCaller_beforeTheImportReturns() = runBlocking {
        val stages = mutableListOf<GetLineImportStage>()
        val backend = FakeProfileManager(
            onCommit = { uuid, callback ->
                callback?.updateStatus(
                    FetchStatus(FetchStatus.Action.Verifying, emptyList(), 1, 1),
                )
                markImported(uuid)
            },
        )

        backend.importPending(
            draft,
            null,
            onProgress = { stages += it },
            activate = false,
            diagnosticOp = null,
        )

        assertEquals(listOf(GetLineImportStage.Checking), stages)
    }

    /**
     * #85: commit side effect then DeadObjectException must not re-enter create.
     * Orphan delete on the failed call is existing importPending behaviour.
     */
    @Test
    fun importedReuseWithMissingDirectory_deletesAndCreatesFresh() = runBlocking {
        val broken = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(broken, imported = true)
        backend.storageHealth[broken] = ProfileStorageHealth.MissingDirectory

        val imported = backend.importPending(
            draft,
            GetLineSubscriptionId(broken.toString()),
            {},
            activate = false,
            diagnosticOp = null,
        )

        assertEquals(listOf(broken), backend.deleted)
        assertEquals(1, backend.created.size)
        assertEquals(imported.value, backend.created.single().toString())
        assertNotEquals(broken.toString(), imported.value)
        assertEquals(listOf(backend.created.single()), backend.patched)
    }

    @Test
    fun pendingReuseWithMissingImportedDirectory_keepsExistingRow() = runBlocking {
        val pending = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(pending, imported = false)

        val imported = backend.importPending(
            draft,
            GetLineSubscriptionId(pending.toString()),
            {},
            activate = false,
            diagnosticOp = null,
        )

        assertTrue(backend.deleted.isEmpty())
        assertTrue(backend.created.isEmpty())
        assertEquals(pending.toString(), imported.value)
        assertEquals(listOf(pending), backend.patched)
    }

    @Test
    fun productImport_withExistingManaged_mintsAFreshUuid() = runBlocking {
        val managed = UUID.randomUUID()
        val backend = FakeProfileManager()
        backend.seed(managed, imported = true)

        val id = backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)

        assertNotEquals(managed.toString(), id.value)
        assertEquals(listOf(id.value), backend.created.map { it.toString() })
        assertTrue(backend.deleted.isEmpty())
        assertEquals(managed, backend.queryByUUID(managed)?.uuid)
    }

    @Test
    fun productImport_cancelWithExistingManaged_deletesOnlyTheCandidate() = runBlocking {
        val managed = UUID.randomUUID()
        val entered = CompletableDeferred<Unit>()
        val backend = FakeProfileManager(
            onCommit = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        backend.seed(managed, imported = true)

        coroutineScope {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
            }
            entered.await()
            job.cancelAndJoin()
        }

        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
        assertEquals(managed, backend.queryByUUID(managed)?.uuid)
    }

    @Test
    fun productImport_timeoutWithExistingManaged_deletesOnlyTheCandidate() = runBlocking {
        val managed = UUID.randomUUID()
        val backend = FakeProfileManager(
            onCommit = { _, _ -> awaitCancellation() },
        )
        backend.seed(managed, imported = true)

        try {
            withTimeout(50) {
                backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
            }
            fail("expected timeout")
        } catch (_: TimeoutCancellationException) {
            // expected
        }

        assertEquals(backend.created, backend.deleted)
        assertEquals(1, backend.deleted.size)
        assertEquals(managed, backend.queryByUUID(managed)?.uuid)
    }

    @Test
    fun productImport_deletedCandidate_cannotBeActivatedOverTheOldManaged() = runBlocking {
        val managed = UUID.randomUUID()
        val entered = CompletableDeferred<Unit>()
        val backend = FakeProfileManager(
            onCommit = { _, _ ->
                entered.complete(Unit)
                awaitCancellation()
            },
        )
        backend.seed(managed, imported = true)

        var candidate: UUID? = null
        coroutineScope {
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
            }
            entered.await()
            candidate = backend.created.single()
            job.cancelAndJoin()
        }

        val gone = candidate!!
        assertEquals(null, backend.queryByUUID(gone))
        assertEquals(managed, backend.queryByUUID(managed)?.uuid)
        assertTrue(backend.activated.isEmpty())
    }

    /**
     * Product import always mints a fresh UUID; a second call is a new attempt.
     */
    @Test
    fun resumeWithoutRecordedUuid_createsSecondProfile() = runBlocking {
        val backend = FakeProfileManager()
        backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)

        backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)

        assertEquals(2, backend.created.size)
    }

    @Test
    fun pendingReuseId_resumesImport_withoutSecondCreate() = runBlocking {
        val backend = FakeProfileManager()
        val imported = backend.importPending(
            draft,
            null,
            {},
            activate = false,
            diagnosticOp = null,
        )

        val recovered = backend.importPending(
            draft,
            imported,
            {},
            activate = false,
            diagnosticOp = null,
        )

        assertEquals(imported, recovered)
        assertEquals(1, backend.created.size)
        assertEquals(listOf(backend.created.single(), backend.created.single()), backend.committed)
        assertTrue(backend.deleted.isEmpty())
    }

    @Test
    fun oneShot_commitThenDeadObject_doesNotCreateAgain() = runBlocking {
        val backend = FakeProfileManager(onCommit = { uuid, _ ->
            markImported(uuid)
            throw android.os.DeadObjectException("died after commit")
        })
        var attempts = 0

        try {
            runProfileRemoteBlock(
                retryOnDeadObject = false,
                onDeadObject = {},
            ) {
                attempts++
                backend.importPending(draft, null, {}, activate = false, diagnosticOp = null)
            }
            fail("expected BinderDiedException")
        } catch (_: BinderDiedException) {
            // expected
        }

        assertEquals(1, attempts)
        assertEquals(1, backend.created.size)
        assertEquals(1, backend.committed.size)
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }
}

/**
 * Records the profile operations the import performs. Hand-written rather than
 * mocked: [IProfileManager] is a plain suspend interface, and the assertions are
 * all about which calls happened in which order.
 */
private class FakeProfileManager(
    private val onCommit: suspend FakeProfileManager.(UUID, IFetchObserver?) -> Unit =
        { uuid, _ -> markImported(uuid) },
    private val onSetActive: (Profile) -> Unit = {},
) : IProfileManager {
    private val profiles = linkedMapOf<UUID, Profile>()
    val storageHealth = mutableMapOf<UUID, ProfileStorageHealth>()

    val created = mutableListOf<UUID>()
    val patched = mutableListOf<UUID>()
    val committed = mutableListOf<UUID>()
    val deleted = mutableListOf<UUID>()
    val activated = mutableListOf<UUID>()
    val updated = mutableListOf<UUID>()
    var createdType: Profile.Type? = null
        private set

    fun seed(
        uuid: UUID,
        imported: Boolean,
        type: Profile.Type = Profile.Type.Url,
    ) {
        profiles[uuid] = profile(uuid, "seeded", type, imported)
    }

    fun markImported(uuid: UUID) {
        profiles[uuid] = profiles.getValue(uuid).copy(imported = true)
    }

    override suspend fun create(
        type: Profile.Type,
        name: String,
        source: String,
        ageSecretKey: String?,
    ): UUID {
        val uuid = UUID.randomUUID()
        createdType = type
        created += uuid
        profiles[uuid] = profile(uuid, name, type, imported = false)
        return uuid
    }

    override suspend fun patch(
        uuid: UUID,
        name: String,
        source: String,
        interval: Long,
        ageSecretKey: String?,
    ) {
        patched += uuid
    }

    override suspend fun commit(uuid: UUID, callback: IFetchObserver?) {
        committed += uuid
        onCommit.invoke(this, uuid, callback)
    }

    /**
     * A real Binder call fails on a cancelled job. Recording the delete
     * unconditionally would keep the cancellation test green even without
     * `NonCancellable` in the cleanup — which is the one thing it exists to pin.
     */
    override suspend fun delete(uuid: UUID) {
        currentCoroutineContext().ensureActive()
        deleted += uuid
        profiles -= uuid
    }

    override suspend fun setActive(profile: Profile) {
        onSetActive(profile)
        activated += profile.uuid
    }

    override suspend fun queryByUUID(uuid: UUID): Profile? = profiles[uuid]

    override suspend fun queryAll(): List<Profile> = profiles.values.toList()

    override suspend fun queryActive(): Profile? =
        activated.lastOrNull()?.let { profiles[it] }

    override suspend fun queryProfileStateSnapshot(managedUuid: UUID?): ProfileStateSnapshot {
        val imported = profiles.values.filter { it.imported }.map { it.uuid }
        val active = activated.lastOrNull()
        val checked = listOfNotNull(active, managedUuid)
            .distinct()
            .filter { it in imported }
            .map { uuid ->
                ProfileStorageSnapshot(
                    uuid = uuid,
                    health = storageHealth[uuid] ?: ProfileStorageHealth.Intact,
                )
            }
        return ProfileStateSnapshot(
            activeUuid = active,
            importedUuids = imported,
            checked = checked,
        )
    }

    override suspend fun clone(uuid: UUID): UUID {
        error("not used by import")
    }

    override suspend fun release(uuid: UUID) {
        error("not used by import")
    }

    override suspend fun update(uuid: UUID) {
        error("not used by import")
    }

    override suspend fun updateSilently(uuid: UUID) {
        updated += uuid
    }

    override suspend fun queryActiveSelectionSnapshot(): ProfileSelectionSnapshot? = null

    override suspend fun setSelected(uuid: UUID, group: String, name: String): Boolean = false

    private fun profile(
        uuid: UUID,
        name: String,
        type: Profile.Type,
        imported: Boolean,
    ) = Profile(
        uuid = uuid,
        name = name,
        type = type,
        source = "",
        active = false,
        interval = 0L,
        upload = 0L,
        download = 0L,
        total = 0L,
        expire = 0L,
        updatedAt = 0L,
        imported = imported,
        pending = !imported,
    )
}
