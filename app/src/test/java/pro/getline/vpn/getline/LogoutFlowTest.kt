package pro.getline.vpn.getline

import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.getline.vpn.getline.LogoutFlow.Action
import pro.getline.vpn.getline.LogoutFlow.Outcome
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
import pro.getline.vpn.getline.auth.SubscriptionsResponse
import pro.getline.vpn.getline.auth.testSessionStore
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LogoutFlowTest {
    private lateinit var store: GetLineSessionStore
    private lateinit var sessions: GetLineSessionRepository
    private lateinit var subscriptions: LogoutFakeSubscriptionRepository
    private lateinit var events: MutableList<String>
    private lateinit var host: LogoutFakeHost

    @Before
    fun setUp() {
        store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        sessions = GetLineSessionRepository(LogoutFakeAuthApi(), store)
        events = mutableListOf()
        subscriptions = LogoutFakeSubscriptionRepository(events)
        host = LogoutFakeHost(events)
    }

    @Test
    fun removeSubscription_deletesOldThenCurrent_beforeClearingAllState() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed", source = "https://subscription.example")
        sessions.rememberPendingProfileCleanup("old")
        host.onClear = {
            assertFalse(sessions.hasSession())
            assertNull(sessions.managedProfileUuid())
        }

        val outcome = flow().perform(Action.RemoveSubscription)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(
            listOf("vpn-stop", "delete:old", "delete:managed", "ui-cleared"),
            events,
        )
        assertFalse(sessions.hasSession())
        assertNull(sessions.managedProfileUuid())
        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun removeSubscription_pendingFailure_drainsOtherOldProfilesButKeepsSessionAndCurrent() =
        runBlocking {
            seedSession()
            sessions.rememberManagedProfile("managed")
            sessions.rememberPendingProfileCleanup("old-fails")
            sessions.rememberPendingProfileCleanup("old-deletes")
            subscriptions.delete = { id ->
                if (id.value == "old-fails") {
                    GetLineBackendResult.Unavailable
                } else {
                    GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
                }
            }

            val outcome = flow().perform(Action.RemoveSubscription)

            assertEquals(Outcome.RemoveSubscriptionFailed, outcome)
            assertEquals(setOf("old-fails", "old-deletes"), subscriptions.deleted.toSet())
            assertFalse("current profile must not be deleted", "managed" in subscriptions.deleted)
            assertTrue(sessions.hasSession())
            assertEquals("managed", sessions.managedProfileUuid())
            assertEquals(setOf("old-fails"), sessions.pendingProfileCleanupUuids())
            assertFalse("Activity session UI must stay intact", "ui-cleared" in events)
        }

    @Test
    fun removeSubscription_currentFailure_keepsSessionAndBinding_forRetry() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed")
        subscriptions.delete = { GetLineBackendResult.Unavailable }

        val outcome = flow().perform(Action.RemoveSubscription)

        assertEquals(Outcome.RemoveSubscriptionFailed, outcome)
        assertEquals(listOf("managed"), subscriptions.deleted)
        assertTrue(sessions.hasSession())
        assertEquals("managed", sessions.managedProfileUuid())
        assertFalse("ui-cleared" in events)
    }

    @Test
    fun removeSubscription_notFound_isCompletedIdempotentRemoval() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("already-gone")
        subscriptions.delete = {
            GetLineBackendResult.Success(ManagedProfileDeleteOutcome.NotFound)
        }

        val outcome = flow().perform(Action.RemoveSubscription)

        assertEquals(Outcome.Completed, outcome)
        assertFalse(sessions.hasSession())
        assertNull(sessions.managedProfileUuid())
        assertEquals(1, host.clearCount)
    }

    @Test
    fun signOut_dropsTokensBeforeDeletes_butKeepsBindingUntilCurrentIsGone() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed", source = "https://subscription.example")
        sessions.rememberPendingProfileCleanup("old")
        host.onClear = {
            assertFalse(sessions.hasSession())
            assertEquals("managed", sessions.managedProfileUuid())
        }
        subscriptions.beforeDelete = {
            assertFalse("tokens must be gone before profile deletion", sessions.hasSession())
            assertEquals("binding must still address the profile", "managed", sessions.managedProfileUuid())
        }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(
            listOf("vpn-stop", "ui-cleared", "delete:old", "delete:managed"),
            events,
        )
        assertFalse(sessions.hasSession())
        assertNull(sessions.managedProfileUuid())
        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun signOut_pendingFailure_keepsBindingAndFailedTombstone_afterSessionIsGone() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed")
        sessions.rememberPendingProfileCleanup("old")
        subscriptions.delete = { GetLineBackendResult.Unavailable }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.SignOutFailed, outcome)
        assertEquals(listOf("old"), subscriptions.deleted)
        assertFalse(sessions.hasSession())
        assertEquals("managed", sessions.managedProfileUuid())
        assertEquals(setOf("old"), sessions.pendingProfileCleanupUuids())
        assertEquals(1, host.clearCount)
    }

    @Test
    fun signOut_currentFailure_keepsOnlyBinding_forLinkOnlyRetry() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed", source = "https://subscription.example")
        subscriptions.delete = { GetLineBackendResult.Unavailable }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.SignOutFailed, outcome)
        assertFalse(sessions.hasSession())
        assertEquals("managed", sessions.managedProfileUuid())
        assertEquals("https://subscription.example", sessions.managedProfileSource())
        assertEquals(1, host.clearCount)
    }

    @Test
    fun signOut_deleteException_isBestEffortFailure_andKeepsBinding() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed")
        subscriptions.delete = { error("profile backend failed") }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.SignOutFailed, outcome)
        assertFalse(sessions.hasSession())
        assertEquals("managed", sessions.managedProfileUuid())
    }

    @Test
    fun signOut_deleteCancellation_isBestEffortFailure_andKeepsBinding() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed")
        subscriptions.delete = { throw CancellationException("Activity destroyed") }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.SignOutFailed, outcome)
        assertFalse(sessions.hasSession())
        assertEquals("managed", sessions.managedProfileUuid())
    }

    @Test
    fun signOut_notFound_clearsBindingAndCompletes() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("already-gone")
        subscriptions.delete = {
            GetLineBackendResult.Success(ManagedProfileDeleteOutcome.NotFound)
        }

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.Completed, outcome)
        assertFalse(sessions.hasSession())
        assertNull(sessions.managedProfileUuid())
    }

    @Test
    fun signOut_withoutManagedProfile_clearsSessionWithoutDelete() = runBlocking {
        seedSession()

        val outcome = flow().perform(Action.SignOut)

        assertEquals(Outcome.Completed, outcome)
        assertTrue(subscriptions.deleted.isEmpty())
        assertFalse(sessions.hasSession())
        assertEquals(listOf("vpn-stop", "ui-cleared"), events)
    }

    @Test
    fun matchingTombstone_isProtectedThenCurrentProfileIsDeletedOnce() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("managed")
        sessions.rememberPendingProfileCleanup("managed")

        val outcome = flow().perform(Action.RemoveSubscription)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(listOf("managed"), subscriptions.deleted)
        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun perform_readsBindingAtExecutionTime() = runBlocking {
        seedSession()
        sessions.rememberManagedProfile("before-confirmation")
        val logout = flow()

        // Models a late import/refresh while Home's confirmation dialog is open.
        sessions.rememberManagedProfile("current-at-confirmation")

        val outcome = logout.perform(Action.RemoveSubscription)

        assertEquals(Outcome.Completed, outcome)
        assertEquals(listOf("current-at-confirmation"), subscriptions.deleted)
    }

    private fun flow(): LogoutFlow = LogoutFlow(
        backend = LogoutFakeBackend(subscriptions, events),
        sessionRepository = sessions,
        host = host,
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
}

private class LogoutFakeHost(
    private val events: MutableList<String>,
) : LogoutFlow.Host {
    var clearCount = 0
    var onClear: () -> Unit = {}

    override suspend fun onSessionCleared() {
        onClear()
        clearCount += 1
        events += "ui-cleared"
    }
}

private class LogoutFakeBackend(
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
    override val appRouting: GetLineAppRoutingRepository
        get() = error("not used")
}

private class LogoutFakeSubscriptionRepository(
    private val events: MutableList<String>,
) : GetLineSubscriptionRepository {
    val deleted = mutableListOf<String>()
    var beforeDelete: (String) -> Unit = {}
    var delete: suspend (GetLineSubscriptionId) -> GetLineBackendResult<ManagedProfileDeleteOutcome> = {
        GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
    }

    override suspend fun deleteManaged(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<ManagedProfileDeleteOutcome> {
        beforeDelete(id.value)
        deleted += id.value
        events += "delete:${id.value}"
        return delete(id)
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
    override suspend fun activateIfImported(
        id: GetLineSubscriptionId,
    ): GetLineBackendResult<Boolean> = error("not used")
    override suspend fun ensureActiveImported(
        managedUuid: String?,
    ): GetLineBackendResult<Boolean> = error("not used")
    override suspend fun repairLocalActive(
        managedUuid: String?,
    ): GetLineBackendResult<LocalActiveRepair> = error("not used")
    override suspend fun reimportAndActivate(
        draft: GetLineSubscriptionDraft,
        managedId: GetLineSubscriptionId?,
    ): GetLineBackendResult<GetLineSubscriptionId> = error("not used")
    override suspend fun importAndCommit(
        draft: GetLineSubscriptionDraft,
        onProgress: suspend (pro.getline.vpn.getlineui.model.GetLineImportStage) -> Unit,
    ): GetLineBackendResult<GetLineSubscriptionId> = error("not used")
    override suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult =
        error("not used")
}

private class LogoutFakeAuthApi : GetLineAuthApi {
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
    override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse =
        error("not used")
    override suspend fun getDashboard(accessToken: String): DashboardInfo = error("not used")
    override suspend fun activateTrial(accessToken: String) = error("not used")
}
