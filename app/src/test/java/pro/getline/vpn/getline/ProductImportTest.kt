package pro.getline.vpn.getline

import android.content.Intent
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
import pro.getline.vpn.getline.auth.testSessionStore
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProductImportTest {
    private lateinit var store: GetLineSessionStore
    private lateinit var sessions: GetLineSessionRepository

    @Before
    fun setUp() {
        store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        sessions = GetLineSessionRepository(UnusedAuthApi(), store)
    }

    @Test
    fun productImportShouldBind_onlyAfterSuccessfulActivation() {
        assertTrue(productImportShouldBind(GetLineBackendResult.Success(true)))
        assertFalse(productImportShouldBind(GetLineBackendResult.Success(false)))
        assertFalse(productImportShouldBind(GetLineBackendResult.Unavailable))
    }

    @Test
    fun failedActivation_leavesTheOldManagedBinding() {
        sessions.rememberManagedProfile("old-managed", source = "https://old.example/sub")

        if (productImportShouldBind(GetLineBackendResult.Success(false))) {
            commitActivatedProductImport(
                sessions = sessions,
                candidate = GetLineSubscriptionId("candidate"),
                source = "https://new.example/sub",
                subscriptionIdToRemember = "sub-1",
            )
        }

        assertEquals("old-managed", sessions.managedProfileUuid())
        assertEquals("https://old.example/sub", sessions.managedProfileSource())
        assertNull(sessions.rememberedSubscriptionId())
        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun successfulActivation_tombstonesOldManaged_thenBindsCandidate() {
        sessions.rememberManagedProfile("old-managed", source = "https://old.example/sub")

        val previous = commitActivatedProductImport(
            sessions = sessions,
            candidate = GetLineSubscriptionId("candidate"),
            source = "https://new.example/sub",
            subscriptionIdToRemember = "sub-1",
        )

        assertEquals("old-managed", previous)
        assertEquals("candidate", sessions.managedProfileUuid())
        assertEquals("https://new.example/sub", sessions.managedProfileSource())
        assertEquals("sub-1", sessions.rememberedSubscriptionId())
        assertEquals(setOf("old-managed"), sessions.pendingProfileCleanupUuids())
    }

    @Test
    fun postLoginCancel_coldOnboarding_logoutClearsTheSession() {
        seedSession()
        sessions.rememberManagedProfile("candidate-not-bound")

        abandonPostLoginImportSession(sessions, linkOnlySignIn = false)

        assertFalse(sessions.hasSession())
        assertNull(sessions.managedProfileUuid())
    }

    @Test
    fun postLoginCancel_linkOnly_keepsTheWorkingSubscription() {
        seedSession()
        sessions.rememberManagedProfile("link-only", source = "https://link.example/sub")

        abandonPostLoginImportSession(sessions, linkOnlySignIn = true)

        assertFalse(sessions.hasSession())
        assertEquals("link-only", sessions.managedProfileUuid())
        assertEquals("https://link.example/sub", sessions.managedProfileSource())
    }

    @Test
    fun failedActivationRetry_keepsAccountSubscriptionId() {
        val request = GetLineSubscriptionDraft(
            type = GetLineSubscriptionType.Url,
            name = "acct",
            source = "https://example.test/sub",
        )

        val retry = importRetryAfterFailedActivation(
            request = request,
            subscriptionIdToRemember = "sub-1",
        )

        assertEquals(request, retry?.request)
        assertEquals("sub-1", retry?.subscriptionIdToRemember)
    }

    @Test
    fun failedActivationRetry_withoutDraft_staysOnActivateTarget() {
        assertNull(
            importRetryAfterFailedActivation(
                request = null,
                subscriptionIdToRemember = "sub-1",
            ),
        )
    }

    @Test
    fun preferredLoadCancel_startsQueuedImportWhenNotFinishing() {
        assertTrue(
            preferredLoadCancelStartsQueuedImport(
                finishing = false,
                hasQueuedImport = true,
            ),
        )
        assertFalse(
            preferredLoadCancelStartsQueuedImport(
                finishing = true,
                hasQueuedImport = true,
            ),
        )
        assertFalse(
            preferredLoadCancelStartsQueuedImport(
                finishing = false,
                hasQueuedImport = false,
            ),
        )
    }

    @Test
    fun importAttempt_completeWins_cancelDoesNotClaimOrAbandon() {
        seedSession()
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryComplete("ok"))
        val cancelWon = attempt.tryCancel()
        if (cancelWon) {
            abandonPostLoginImportSession(sessions, linkOnlySignIn = false)
        }
        assertFalse(cancelWon)
        assertTrue(sessions.hasSession())
        val outcome = runBlocking { attempt.await() }
        assertEquals(ImportWaitOutcome.Completed("ok"), outcome)
    }

    @Test
    fun importAttempt_cancelWins_lateCompleteDoesNotOverwrite_andAbandons() {
        seedSession()
        val attempt = ImportAttempt<String>()
        val cancelWon = attempt.tryCancel()
        if (cancelWon) {
            abandonPostLoginImportSession(sessions, linkOnlySignIn = false)
        }
        assertTrue(cancelWon)
        assertFalse(attempt.tryComplete("ok"))
        assertFalse(sessions.hasSession())
        val outcome = runBlocking { attempt.await() }
        assertEquals(ImportWaitOutcome.Cancelled, outcome)
    }

    @Test
    fun importAttempt_bothReady_firstCompleteOwnsTerminal() {
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryComplete("ok"))
        assertFalse(attempt.tryCancel())
        assertFalse(attempt.tryComplete("other"))
        val outcome = runBlocking { attempt.await() }
        assertEquals(ImportWaitOutcome.Completed("ok"), outcome)
    }

    @Test
    fun importAttempt_cancelUnblocksWaiterWithoutProducer() = runBlocking {
        val attempt = ImportAttempt<String>()
        val waiter = async { attempt.await() }
        assertTrue(attempt.tryCancel())
        val outcome = withTimeout(500) { waiter.await() }
        assertEquals(ImportWaitOutcome.Cancelled, outcome)
        assertFalse(attempt.tryComplete("late"))
    }

    @Test
    fun importAttempt_failurePropagatesWhenProducerWins() = runBlocking {
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryFail(IllegalStateException("load-failed")))
        assertFalse(attempt.tryCancel())
        try {
            attempt.await()
            fail("expected load failure")
        } catch (error: IllegalStateException) {
            assertEquals("load-failed", error.message)
        }
    }

    @Test
    fun abandonWaiter_incompleteProducer_marksCancelled_lateCompleteIsLost() {
        val attempt = ImportAttempt<String>()
        assertNull(attempt.abandonWaiter())
        assertFalse(attempt.tryComplete("imported"))
        val outcome = runBlocking { attempt.await() }
        assertEquals(ImportWaitOutcome.Cancelled, outcome)
        assertFalse(attempt.markDelivered())
    }

    @Test
    fun abandonWaiter_completeBeforeDelivery_returnsValueForOnLost() {
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryComplete("imported"))
        assertEquals("imported", attempt.abandonWaiter())
        assertFalse(attempt.markDelivered())
        val outcome = runBlocking { attempt.await() }
        assertEquals(ImportWaitOutcome.Completed("imported"), outcome)
    }

    @Test
    fun markDelivered_thenAbandonWaiter_doesNotOrphan() {
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryComplete("imported"))
        val outcome = runBlocking { attempt.await() }
        assertTrue(attempt.markDelivered())
        assertNull(attempt.abandonWaiter())
        assertEquals(ImportWaitOutcome.Completed("imported"), outcome)
    }

    @Test
    fun abandonWaiter_afterCancelWon_doesNotOrphan() {
        val attempt = ImportAttempt<String>()
        assertTrue(attempt.tryCancel())
        assertNull(attempt.abandonWaiter())
        assertFalse(attempt.tryComplete("imported"))
    }

    @Test
    fun failedUnboundDelete_tombstonesCandidateForLaterCleanup() {
        sessions.rememberManagedProfile("old-managed")

        rememberUnboundCandidateIfDeleteIncomplete(
            sessions = sessions,
            candidate = GetLineSubscriptionId("candidate"),
            result = GetLineBackendResult.Unavailable,
        )

        assertEquals(setOf("candidate"), sessions.pendingProfileCleanupUuids())
        assertEquals("old-managed", sessions.managedProfileUuid())
    }

    @Test
    fun failedUnboundDelete_exception_tombstonesCandidate() {
        rememberUnboundCandidateIfDeleteIncomplete(
            sessions = sessions,
            candidate = GetLineSubscriptionId("candidate"),
            result = null,
        )

        assertEquals(setOf("candidate"), sessions.pendingProfileCleanupUuids())
    }

    @Test
    fun successfulUnboundDelete_doesNotTombstone() {
        rememberUnboundCandidateIfDeleteIncomplete(
            sessions = sessions,
            candidate = GetLineSubscriptionId("candidate"),
            result = GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted),
        )
        rememberUnboundCandidateIfDeleteIncomplete(
            sessions = sessions,
            candidate = GetLineSubscriptionId("already-gone"),
            result = GetLineBackendResult.Success(ManagedProfileDeleteOutcome.NotFound),
        )

        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun failedUnboundDelete_doesNotTombstoneCurrentManaged() {
        sessions.rememberManagedProfile("managed")

        rememberUnboundCandidateIfDeleteIncomplete(
            sessions = sessions,
            candidate = GetLineSubscriptionId("managed"),
            result = GetLineBackendResult.Unavailable,
        )

        assertTrue(sessions.pendingProfileCleanupUuids().isEmpty())
    }

    @Test
    fun productImportFlow_success_bindsCandidateAndCleansPrevious() = runBlocking {
        sessions.rememberManagedProfile("old-managed", source = "https://old.example/sub")
        val subscriptions = FlowFakeSubscriptions()
        val stages = mutableListOf<pro.getline.vpn.getlineui.model.GetLineImportStage>()
        val flow = ProductImportFlow(
            backend = FlowFakeBackend(subscriptions),
            sessions = sessions,
            host = FlowFakeHost(),
        )

        val outcome = flow.run(flowDraft(), subscriptionIdToRemember = "sub-1") {
            stages += it
        }

        assertEquals(
            ProductImportFlow.Outcome.Imported(GetLineSubscriptionId("candidate")),
            outcome,
        )
        assertEquals("candidate", sessions.managedProfileUuid())
        assertEquals("https://candidate.example/sub", sessions.managedProfileSource())
        assertEquals("sub-1", sessions.rememberedSubscriptionId())
        assertEquals(listOf("old-managed"), subscriptions.deleted)
        assertEquals(
            listOf(pro.getline.vpn.getlineui.model.GetLineImportStage.LoadingConfig),
            stages,
        )
    }

    @Test
    fun productImportFlow_failedActivation_deletesCandidateAndKeepsBinding() = runBlocking {
        sessions.rememberManagedProfile("old-managed", source = "https://old.example/sub")
        val subscriptions = FlowFakeSubscriptions().apply {
            activationResult = GetLineBackendResult.Success(false)
        }
        val flow = ProductImportFlow(
            backend = FlowFakeBackend(subscriptions),
            sessions = sessions,
            host = FlowFakeHost(),
        )

        val outcome = flow.run(flowDraft(), subscriptionIdToRemember = "sub-1") { }

        assertEquals(
            ProductImportFlow.Outcome.ActivationFailed(
                unavailable = false,
                retry = ImportRetryTarget(flowDraft(), "sub-1"),
            ),
            outcome,
        )
        assertEquals("old-managed", sessions.managedProfileUuid())
        assertEquals(listOf("candidate"), subscriptions.deleted)
        assertEquals(1, subscriptions.activationCalls)
    }

    @Test
    fun productImportFlow_queuedReplacement_deletesCandidateBeforeActivation() = runBlocking {
        val subscriptions = FlowFakeSubscriptions()
        val replacement = flowDraft().copy(source = "https://replacement.example/sub")
        val flow = ProductImportFlow(
            backend = FlowFakeBackend(subscriptions),
            sessions = sessions,
            host = FlowFakeHost(replacement = replacement),
        )

        val outcome = flow.run(flowDraft(), subscriptionIdToRemember = null) { }

        assertEquals(ProductImportFlow.Outcome.Superseded(replacement), outcome)
        assertEquals(listOf("candidate"), subscriptions.deleted)
        assertEquals(0, subscriptions.activationCalls)
        assertNull(sessions.managedProfileUuid())
    }

    @Test
    fun productImportFlow_cancellationDuringForegroundWait_cleansCandidate() = runBlocking {
        sessions.rememberManagedProfile("old-managed")
        val subscriptions = FlowFakeSubscriptions()
        val flow = ProductImportFlow(
            backend = FlowFakeBackend(subscriptions),
            sessions = sessions,
            host = FlowFakeHost(foreground = false),
        )

        try {
            flow.run(flowDraft(), subscriptionIdToRemember = null) { }
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The flow owns candidate deletion when its Activity waiter dies.
        }

        assertEquals(listOf("candidate"), subscriptions.deleted)
        assertEquals("old-managed", sessions.managedProfileUuid())
    }

    private fun flowDraft() = GetLineSubscriptionDraft(
        type = GetLineSubscriptionType.Url,
        name = "candidate",
        source = "https://candidate.example/sub",
    )

    private class FlowFakeHost(
        private var replacement: GetLineSubscriptionDraft? = null,
        private val foreground: Boolean = true,
    ) : ProductImportFlow.Host {
        override val isForeground: Boolean
            get() = foreground

        override suspend fun awaitForeground() {
            if (!foreground) throw kotlinx.coroutines.CancellationException("screen destroyed")
        }

        override fun takeQueuedReplacement(): GetLineSubscriptionDraft? =
            replacement.also { replacement = null }
    }

    private class FlowFakeBackend(
        override val subscriptions: FlowFakeSubscriptions,
    ) : GetLineBackend {
        override val vpn: GetLineVpnController = object : GetLineVpnController {
            override val running: Boolean = false
            override fun start(): Intent? = null
            override fun stop() = Unit
            override suspend fun querySession(): GetLineSession? = null
        }
        override val servers: VpnServerSelectionRepository
            get() = error("not used")
        override val navigation: GetLineNavigation
            get() = error("not used")
        override val appRouting: GetLineAppRoutingRepository
            get() = error("not used")
    }

    private class FlowFakeSubscriptions : GetLineSubscriptionRepository {
        var activationResult: GetLineBackendResult<Boolean> =
            GetLineBackendResult.Success(true)
        var activationCalls = 0
        val deleted = mutableListOf<String>()

        override suspend fun importAndCommit(
            draft: GetLineSubscriptionDraft,
            onProgress: suspend (pro.getline.vpn.getlineui.model.GetLineImportStage) -> Unit,
        ): GetLineBackendResult<GetLineSubscriptionId> {
            onProgress(pro.getline.vpn.getlineui.model.GetLineImportStage.LoadingConfig)
            return GetLineBackendResult.Success(GetLineSubscriptionId("candidate"))
        }

        override suspend fun activateIfImported(
            id: GetLineSubscriptionId,
        ): GetLineBackendResult<Boolean> {
            activationCalls++
            return activationResult
        }

        override suspend fun deleteManaged(
            id: GetLineSubscriptionId,
        ): GetLineBackendResult<ManagedProfileDeleteOutcome> {
            deleted += id.value
            return GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
        }

        override suspend fun snapshot() = error("not used")
        override suspend fun findImported(id: GetLineSubscriptionId) = error("not used")
        override suspend fun hasImported() = error("not used")
        override suspend fun hasActiveImported() = error("not used")
        override suspend fun createPending(draft: GetLineSubscriptionDraft) = error("not used")
        override suspend fun createOrUpdatePending(
            draft: GetLineSubscriptionDraft,
            reuseId: GetLineSubscriptionId?,
        ) = error("not used")
        override suspend fun ensureActiveImported(managedUuid: String?) = error("not used")
        override suspend fun repairLocalActive(managedUuid: String?) = error("not used")
        override suspend fun reimportAndActivate(
            draft: GetLineSubscriptionDraft,
            managedId: GetLineSubscriptionId?,
        ) = error("not used")
        override suspend fun requestConfigUpdate(id: GetLineSubscriptionId) = error("not used")
    }

    private fun seedSession() {
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400,
            ),
        )
    }

    private class UnusedAuthApi : GetLineAuthApi {
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
        override suspend fun getSubscriptions(accessToken: String) = error("not used")
        override suspend fun getDashboard(accessToken: String): DashboardInfo = error("not used")
        override suspend fun activateTrial(accessToken: String) = error("not used")
    }
}
