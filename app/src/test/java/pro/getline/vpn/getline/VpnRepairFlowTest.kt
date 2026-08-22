package pro.getline.vpn.getline

import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.getline.vpn.getline.VpnRepairFlow.RepairOutcome
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.BrowserAuthStartResponse
import pro.getline.vpn.getline.auth.CurrentUser
import pro.getline.vpn.getline.auth.DashboardInfo
import pro.getline.vpn.getline.auth.DeviceKey
import pro.getline.vpn.getline.auth.EmailOtpSendResult
import pro.getline.vpn.getline.auth.EmailOtpVerifyResult
import pro.getline.vpn.getline.auth.GetLineAuthApi
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.NativeSession
import pro.getline.vpn.getline.auth.SubscriptionItem
import pro.getline.vpn.getline.auth.SubscriptionsResponse
import pro.getline.vpn.getline.auth.testSessionStore
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VpnRepairFlowTest {
    private lateinit var store: GetLineSessionStore
    private lateinit var api: FakeAuthApi
    private lateinit var sessions: GetLineSessionRepository
    private lateinit var subscriptions: FakeSubscriptionRepository
    private lateinit var events: MutableList<String>
    private var online = true

    @Before
    fun setUp() {
        store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        api = FakeAuthApi()
        sessions = GetLineSessionRepository(api, store)
        events = mutableListOf()
        subscriptions = FakeSubscriptionRepository(events)
        online = true
    }

    @Test
    fun localReady_isReadyAcrossOnlineSessionManagedMatrix() = runBlocking {
        for (isOnline in listOf(false, true)) {
            for (hasSession in listOf(false, true)) {
                for (hasManaged in listOf(false, true)) {
                    store.clearAccountState()
                    if (hasSession) seedSession()
                    if (hasManaged) sessions.rememberManagedProfile("managed")
                    online = isOnline
                    subscriptions.repairLocal = { managed ->
                        GetLineBackendResult.Success(
                            LocalActiveRepair.Ready(managed ?: "manual"),
                        )
                    }

                    assertEquals(
                        "online=$isOnline session=$hasSession managed=$hasManaged",
                        RepairOutcome.Ready,
                        flow().repairVpnConfiguration(allowNetwork = true),
                    )
                }
            }
        }
    }

    @Test
    fun localBackendUnavailable_returnsBackendUnavailable_withoutRemoteWork() = runBlocking {
        subscriptions.repairLocal = { GetLineBackendResult.Unavailable }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.BackendUnavailable, outcome)
        assertTrue(subscriptions.reimported.isEmpty())
    }

    @Test
    fun noSessionOrSavedSource_returnsNeedsSetup() = runBlocking {
        subscriptions.managedAbsent(imported = false)

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.NeedsSetup, outcome)
        assertTrue(subscriptions.reimported.isEmpty())
    }

    @Test
    fun quietPassWithRemotePath_returnsFailedPrepare_withoutRemoteWork() = runBlocking {
        sessions.rememberManagedProfile("managed", source = "https://custom.example/sub")
        subscriptions.managedAbsent(imported = false)

        val outcome = flow().repairVpnConfiguration(allowNetwork = false)

        assertEquals(RepairOutcome.FailedPrepare, outcome)
        assertTrue(subscriptions.reimported.isEmpty())
    }

    @Test
    fun offlinePassWithRemotePath_returnsFailedRestore_withoutRemoteWork() = runBlocking {
        sessions.rememberManagedProfile("managed", source = "https://custom.example/sub")
        subscriptions.managedAbsent(imported = false)
        online = false

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.FailedRestore, outcome)
        assertTrue(subscriptions.reimported.isEmpty())
    }

    @Test
    fun importedManagedThatCannotActivate_returnsFailedPrepare_afterDefensiveRetry() =
        runBlocking {
            sessions.rememberManagedProfile("managed")
            subscriptions.managedAbsent(imported = true)

            val outcome = flow().repairVpnConfiguration(allowNetwork = true)

            assertEquals(RepairOutcome.FailedPrepare, outcome)
            assertEquals(2, subscriptions.repairCalls)
            assertTrue(subscriptions.reimported.isEmpty())
        }

    @Test
    fun defensiveLocalRetryCanRecoverToReady() = runBlocking {
        sessions.rememberManagedProfile("managed")
        var call = 0
        subscriptions.repairLocal = {
            call += 1
            GetLineBackendResult.Success(
                if (call == 1) {
                    LocalActiveRepair.ManagedAbsent("managed", managedIsImported = true)
                } else {
                    LocalActiveRepair.Ready("managed")
                },
            )
        }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.Ready, outcome)
        assertEquals(2, subscriptions.repairCalls)
    }

    @Test
    fun oldProfileIsDeletedOnlyOnPassThatProvesReplacementReady() = runBlocking {
        val source = "https://custom.example/sub"
        sessions.rememberManagedProfile("managed", source)
        sessions.rememberPendingProfileCleanup("old-active")
        var pass = 1
        subscriptions.repairLocal = {
            GetLineBackendResult.Success(
                if (pass == 1) {
                    LocalActiveRepair.Ready("old-active")
                } else {
                    LocalActiveRepair.Ready("managed")
                },
            )
        }
        subscriptions.activateImported = { false }
        subscriptions.reimport = { _, reuse ->
            GetLineBackendResult.Success(reuse ?: error("managed UUID must be reused"))
        }

        assertEquals(
            RepairOutcome.Ready,
            flow().repairVpnConfiguration(allowNetwork = true),
        )
        assertTrue("old profile must survive until replacement is proven", subscriptions.deleted.isEmpty())
        assertEquals(setOf("old-active"), sessions.pendingProfileCleanupUuids())

        events.clear()
        pass = 2
        assertEquals(
            RepairOutcome.Ready,
            flow().repairVpnConfiguration(allowNetwork = true),
        )

        assertEquals(listOf("old-active"), subscriptions.deleted)
        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
        assertEquals(listOf("repair:managed", "delete:old-active"), events)
    }

    @Test
    fun unavailableActivationOfReplacement_keepsOldProfileAndReturnsBackendUnavailable() =
        runBlocking {
            sessions.rememberManagedProfile("managed", "https://custom.example/sub")
            sessions.rememberPendingProfileCleanup("old-active")
            subscriptions.repairLocal = {
                GetLineBackendResult.Success(LocalActiveRepair.Ready("old-active"))
            }
            subscriptions.activateImportedResult = { GetLineBackendResult.Unavailable }

            val outcome = flow().repairVpnConfiguration(allowNetwork = true)

            assertEquals(RepairOutcome.BackendUnavailable, outcome)
            assertTrue(subscriptions.deleted.isEmpty())
            assertEquals(setOf("old-active"), sessions.pendingProfileCleanupUuids())
        }

    @Test
    fun corruptManaged_quietPass_returnsFailedPrepare_withoutRemote() = runBlocking {
        sessions.rememberManagedProfile("managed", "https://custom.example/sub")
        subscriptions.repairLocal = {
            GetLineBackendResult.Success(
                LocalActiveRepair.ManagedCorrupt("managed", "missing_dir"),
            )
        }

        val outcome = flow().repairVpnConfiguration(allowNetwork = false)

        assertEquals(RepairOutcome.FailedPrepare, outcome)
        assertTrue(subscriptions.reimported.isEmpty())
    }

    @Test
    fun corruptManaged_reimportsInsteadOfLocalActivate() = runBlocking {
        val source = "https://custom.example/sub"
        sessions.rememberManagedProfile("managed", source)
        subscriptions.repairLocal = {
            GetLineBackendResult.Success(
                LocalActiveRepair.ManagedCorrupt("managed", "missing_dir"),
            )
        }
        subscriptions.reimport = { _, reuse ->
            GetLineBackendResult.Success(reuse ?: error("managed UUID must be reused"))
        }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.Ready, outcome)
        assertEquals(1, subscriptions.repairCalls)
        assertEquals(source, subscriptions.reimported.single().first.source)
        assertEquals("managed", subscriptions.reimported.single().second?.value)
    }

    @Test
    fun boundUrlSourceWinsOverAccountPreferredSubscription() = runBlocking {
        val customSource = "https://user.example/custom"
        sessions.rememberManagedProfile("managed", customSource)
        sessions.rememberSubscription("previous-account-id")
        seedSession()
        api.subscriptions = listOf(subscription("account-id", environmentLink(), "Account"))
        subscriptions.managedAbsent(imported = false)
        subscriptions.reimport = { _, reuse ->
            GetLineBackendResult.Success(reuse ?: error("managed UUID must be reused"))
        }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.Ready, outcome)
        assertEquals(0, api.subscriptionCalls)
        assertEquals(customSource, subscriptions.reimported.single().first.source)
        assertEquals("GetLine profile", subscriptions.reimported.single().first.name)
        assertEquals("managed", subscriptions.reimported.single().second?.value)
        assertEquals("previous-account-id", sessions.rememberedSubscriptionId())
    }

    @Test
    fun accountPreferredSourceIsRemembered_andRetryReusesManagedUuid() = runBlocking {
        sessions.rememberManagedProfile("managed")
        seedSession()
        val source = environmentLink()
        api.subscriptions = listOf(subscription("account-id", source, "Paid plan"))
        subscriptions.managedAbsent(imported = false)
        subscriptions.reimport = { _, reuse ->
            GetLineBackendResult.Success(reuse ?: error("managed UUID must be reused"))
        }

        assertEquals(
            RepairOutcome.Ready,
            flow().repairVpnConfiguration(allowNetwork = true),
        )
        assertEquals(
            RepairOutcome.Ready,
            flow().repairVpnConfiguration(allowNetwork = true),
        )

        assertEquals(listOf("managed", "managed"), subscriptions.reimported.map { it.second?.value })
        assertEquals(listOf(source, source), subscriptions.reimported.map { it.first.source })
        assertEquals("Paid plan", subscriptions.reimported.first().first.name)
        assertEquals("account-id", sessions.rememberedSubscriptionId())
        assertEquals(1, api.subscriptionCalls)
    }

    @Test
    fun sessionWithoutManagedUuid_createsBindingWithoutInventingReuseId() = runBlocking {
        seedSession()
        val source = environmentLink()
        api.subscriptions = listOf(subscription("account-id", source, null))
        subscriptions.managedAbsent(imported = false)
        subscriptions.reimport = { _, reuse ->
            assertNull(reuse)
            GetLineBackendResult.Success(GetLineSubscriptionId("created"))
        }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.Ready, outcome)
        assertEquals("created", sessions.managedProfileUuid())
        assertEquals(source, sessions.managedProfileSource())
        assertEquals("GetLine profile", subscriptions.reimported.single().first.name)
    }

    @Test
    fun remoteBackendUnavailable_returnsFailedRestore_andKeepsExistingBinding() = runBlocking {
        val source = "https://user.example/custom"
        sessions.rememberManagedProfile("managed", source)
        subscriptions.managedAbsent(imported = false)
        subscriptions.reimport = { _, _ -> GetLineBackendResult.Unavailable }

        val outcome = flow().repairVpnConfiguration(allowNetwork = true)

        assertEquals(RepairOutcome.FailedRestore, outcome)
        assertEquals("managed", sessions.managedProfileUuid())
        assertEquals(source, sessions.managedProfileSource())
    }

    private fun flow(): VpnRepairFlow = VpnRepairFlow(
        backend = FakeBackend(subscriptions, events),
        sessionRepository = sessions,
        host = object : VpnRepairFlow.Host {
            override fun hasValidatedInternetConnection(): Boolean = online
            override fun defaultProfileName(): String = "GetLine profile"
        },
    )

    private fun seedSession() {
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400,
            ),
        )
    }

    private fun environmentLink(): String =
        if (pro.getline.vpn.GetLineControlPlaneHostPolicy.isE2e) {
            "https://app.stage.getline.pro/sub/e2e"
        } else {
            "https://app.getline.pro/sub/prod"
        }

    private fun subscription(id: String, source: String?, name: String?): SubscriptionItem =
        SubscriptionItem(
            id = id,
            name = null,
            planName = name,
            planType = null,
            kind = null,
            isPrimary = true,
            isActive = true,
            expireAtEpochMillis = null,
            daysLeft = null,
            deviceLimit = null,
            totalDeviceLimit = null,
            devicesCount = null,
            traffic = null,
            autopayEnabled = false,
            renewalDisabled = false,
            planArchived = false,
            subscriptionLink = source,
        )
}

private class FakeBackend(
    override val subscriptions: GetLineSubscriptionRepository,
    events: MutableList<String>,
) : GetLineBackend {
    override val vpn: GetLineVpnController = object : GetLineVpnController {
        override val running: Boolean = false
        override fun start(): Intent? = error("not used")
        override fun stop() {
            events += "vpn-stop"
        }
        override suspend fun querySession(): GetLineSession? = null
    }
    override val servers: VpnServerSelectionRepository
        get() = error("not used")
    override val navigation: GetLineNavigation
        get() = error("not used")
}

private class FakeSubscriptionRepository(
    private val events: MutableList<String>,
) : GetLineSubscriptionRepository {
    var repairCalls = 0
    val reimported = mutableListOf<Pair<GetLineSubscriptionDraft, GetLineSubscriptionId?>>()
    val deleted = mutableListOf<String>()

    var repairLocal: suspend (String?) -> GetLineBackendResult<LocalActiveRepair> = {
        GetLineBackendResult.Success(LocalActiveRepair.ManagedAbsent(it, false))
    }
    var activateImported: suspend (GetLineSubscriptionId) -> Boolean = { false }
    var activateImportedResult: suspend (GetLineSubscriptionId) -> GetLineBackendResult<Boolean> = {
        GetLineBackendResult.Success(activateImported(it))
    }
    var reimport: suspend (
        GetLineSubscriptionDraft,
        GetLineSubscriptionId?,
    ) -> GetLineBackendResult<GetLineSubscriptionId> = { _, reuse ->
        GetLineBackendResult.Success(reuse ?: GetLineSubscriptionId("created"))
    }

    fun managedAbsent(imported: Boolean) {
        repairLocal = { managed ->
            GetLineBackendResult.Success(LocalActiveRepair.ManagedAbsent(managed, imported))
        }
    }

    override suspend fun repairLocalActive(
        managedUuid: String?,
    ): GetLineBackendResult<LocalActiveRepair> {
        repairCalls += 1
        events += "repair:${managedUuid ?: "none"}"
        return repairLocal(managedUuid)
    }

    override suspend fun activateIfImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Boolean> {
        events += "activate:${id.value}"
        return activateImportedResult(id)
    }

    override suspend fun reimportAndActivate(
        draft: GetLineSubscriptionDraft,
        managedId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> {
        reimported += draft to managedId
        events += "reimport:${managedId?.value ?: "new"}"
        return reimport(draft, managedId)
    }

    override suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<ManagedProfileDeleteOutcome> {
        deleted += id.value
        events += "delete:${id.value}"
        return GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
    }

    override suspend fun snapshot(): GetLineBackendResult<GetLineSubscriptionSnapshot> =
        error("not used")
    override suspend fun findImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<GetLineSubscriptionSummary?> = error("not used")
    override suspend fun hasImported(): GetLineBackendResult<Boolean> = error("not used")
    override suspend fun hasActiveImported(): GetLineBackendResult<Boolean> = error("not used")
    override suspend fun createPending(
        draft: GetLineSubscriptionDraft,
    ): GetLineBackendResult<GetLineSubscriptionId> = error("not used")
    override suspend fun createOrUpdatePending(
        draft: GetLineSubscriptionDraft,
        reuseId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> = error("not used")
    override suspend fun ensureActiveImported(
        managedUuid: String?,
    ): GetLineBackendResult<Boolean> = error("not used")
    override suspend fun importAndCommit(
        draft: GetLineSubscriptionDraft,
        onProgress: suspend (pro.getline.vpn.getlineui.model.GetLineImportStage) -> Unit,
    ): GetLineBackendResult<GetLineSubscriptionId> = error("not used")
    override suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult =
        error("not used")
}

private class FakeAuthApi : GetLineAuthApi {
    var subscriptions: List<SubscriptionItem> = emptyList()
    var subscriptionCalls = 0

    override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
        subscriptionCalls += 1
        return SubscriptionsResponse(autopayAvailable = false, subscriptions = subscriptions)
    }

    override suspend fun startBrowserAuth(
        method: AuthMethod,
        codeChallenge: String,
        appRedirect: String,
    ): BrowserAuthStartResponse = error("not used")
    override suspend fun exchangeNativeCode(
        code: String,
        codeVerifier: String,
    ): NativeSession = error("not used")
    override suspend fun sendEmailOtp(email: String): EmailOtpSendResult = error("not used")
    override suspend fun verifyEmailOtp(email: String, code: String): EmailOtpVerifyResult =
        error("not used")
    override suspend fun getCurrentUser(webToken: String): CurrentUser = error("not used")
    override suspend fun generateDeviceKey(webToken: String): DeviceKey = error("not used")
    override suspend fun exchangeDeviceKey(deviceKey: String): NativeSession = error("not used")
    override suspend fun refresh(refreshToken: String): NativeSession = error("not used")
    override suspend fun getDashboard(accessToken: String): DashboardInfo = error("not used")
    override suspend fun activateTrial(accessToken: String) = error("not used")
}
