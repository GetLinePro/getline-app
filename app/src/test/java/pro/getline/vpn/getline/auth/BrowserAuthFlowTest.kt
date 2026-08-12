package pro.getline.vpn.getline.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.getlineui.model.GetLineProductState

/**
 * Ownership of one browser sign-in attempt across dual delivery, explicit
 * cancel and deep-link handoff. These sequences lived in Activity fields and
 * were not reachable by a JVM test before [BrowserAuthFlow] was extracted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserAuthFlowTest {
    /** AuthCallbackParser pins the native scheme to the build applicationId. */
    private val callbackUri = AppEnvironment.nativeCallbackUri

    private lateinit var pending: PendingNativeAuthStore
    private lateinit var store: GetLineSessionStore
    private lateinit var api: FakeAuthApi
    private lateinit var repository: GetLineSessionRepository
    private lateinit var host: FakeHost

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        pending = PendingNativeAuthStore.testStore(context)
        pending.clear()
        store = testSessionStore(context)
        store.clearAccountState()
        api = FakeAuthApi()
        repository = GetLineSessionRepository(api, store)
        host = FakeHost()
    }

    private fun flow(): BrowserAuthFlow = BrowserAuthFlow(
        sessionRepository = repository,
        pendingNativeAuthStore = pending,
        authApi = api,
        host = host,
        nativeCallbackUri = { callbackUri },
    )

    // --- happy path -------------------------------------------------------

    @Test
    fun authTabCallback_establishesSessionAndRunsPostLoginStepOnce() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.Completed(
            android.net.Uri.parse("$callbackUri?code=one-time-code"),
        )

        flow().signIn(AuthMethod.Google)

        assertEquals(listOf("start", "native_exchange"), api.calls)
        assertEquals(1, host.importCount)
        assertTrue(repository.hasSession())
        // Pending is consumed, not left for a sibling delivery.
        assertNull(pending.peek())
        // Busy is released and the cancel affordance is gone.
        assertFalse(host.busyState)
        assertFalse(host.cancelableState)
    }

    @Test
    fun signIn_isRejectedWhileBusy() = runBlocking {
        host.busyState = true

        flow().signIn(AuthMethod.Google)

        assertEquals(emptyList<String>(), api.calls)
        assertEquals(0, host.importCount)
        assertNull(pending.peek())
    }

    @Test
    fun signIn_offlineDoesNotStartAnAttempt() = runBlocking {
        host.online = false

        flow().signIn(AuthMethod.Google)

        assertEquals(GetLineProductState.Offline, host.states.last())
        assertEquals(emptyList<String>(), api.calls)
        assertNull(pending.peek())
        assertFalse(host.busyState)
    }

    // --- dual delivery ----------------------------------------------------

    @Test
    fun authTabCancelWithSiblingSession_runsPostLoginStepInsteadOfFailing() = runBlocking {
        // Package VIEW already established the session while Auth Tab reported cancel.
        host.launchResult = BrowserAuthLaunchResult.Cancelled
        host.onLaunch = { store.saveSession(nativeSession()) }

        flow().signIn(AuthMethod.Google)

        assertEquals(1, host.importCount)
        assertNull(host.browserLoginFailure)
        // Sibling owns pending; cancel must not clear it out from under the callback.
        assertNotNull(pending.peek())
    }

    @Test
    fun authTabCancelWithoutSession_clearsPendingAndReturnsToEntry() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.Cancelled

        flow().signIn(AuthMethod.Google)

        assertEquals(0, host.importCount)
        assertNull(pending.peek())
        assertTrue(host.refreshedEntry)
        assertNull(host.browserLoginFailure)
    }

    @Test
    fun completedCallbackAfterPendingWasTaken_usesSiblingSessionNotAnError() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.Completed(
            android.net.Uri.parse("$callbackUri?code=one-time-code"),
        )
        // Sibling delivery consumed pending and established the session first.
        host.onLaunch = {
            pending.clearPending()
            store.saveSession(nativeSession())
        }

        flow().signIn(AuthMethod.Google)

        assertEquals(1, host.importCount)
        // No second exchange: the consumed code is not re-submitted.
        assertEquals(listOf("start"), api.calls)
        assertNull(host.browserLoginFailure)
    }

    @Test
    fun completedCallbackWithoutPendingOrSession_failsAsInvalidCallback() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.Completed(
            android.net.Uri.parse("$callbackUri?code=one-time-code"),
        )
        host.onLaunch = { pending.clearPending() }

        flow().signIn(AuthMethod.Google)

        assertEquals(0, host.importCount)
        assertEquals(GetLineProductState.AuthFailed, host.states.last())
        // InvalidCallback is handled inline, not routed to the generic failure path.
        assertNull(host.browserLoginFailure)
    }

    // --- exchange failure keeps the attempt retryable ---------------------

    @Test
    fun exchangeFailure_reputsPendingForSiblingDelivery() = runBlocking {
        api.failNativeExchange = true
        host.launchResult = BrowserAuthLaunchResult.Completed(
            android.net.Uri.parse("$callbackUri?code=one-time-code"),
        )

        flow().signIn(AuthMethod.Google)

        // Same attempt stays claimable within TTL — this is the fix that a
        // "clear pending on any failure" simplification would silently undo.
        assertNotNull(pending.peek())
        assertEquals(AuthMethod.Google, host.browserLoginFailure?.first)
        assertTrue(host.browserLoginFailure?.second is GetLineAuthException.HttpFailure)
        assertFalse(repository.hasSession())
        assertEquals(0, host.importCount)
    }

    @Test
    fun noBrowser_clearsPendingAndPaintsAuthFailed() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.NoBrowser

        flow().signIn(AuthMethod.Google)

        assertNull(pending.peek())
        assertEquals(GetLineProductState.AuthFailed, host.states.last())
        assertEquals(0, host.importCount)
    }

    // --- deep-link handoff ------------------------------------------------

    @Test
    fun deepLinkHandoff_releasesTheWaitingAttemptAndImportsOnce() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.AwaitingDeepLink
        val launched = CompletableDeferred<Unit>()
        host.onLaunch = { launched.complete(Unit) }
        val flow = flow()

        val attempt = launch { flow.signIn(AuthMethod.Google) }
        launched.await()

        // Callback Activity established the session, then handed off.
        store.saveSession(nativeSession())
        flow.onDeepLinkHandoff(success = true)
        attempt.join()

        assertEquals(1, host.importCount)
        assertFalse(host.busyState)
    }

    @Test
    fun deepLinkHandoffWithoutSession_failsTheWaiterInsteadOfHanging() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.AwaitingDeepLink
        val launched = CompletableDeferred<Unit>()
        host.onLaunch = { launched.complete(Unit) }
        val flow = flow()

        val attempt = launch { flow.signIn(AuthMethod.Google) }
        launched.await()

        flow.onDeepLinkHandoff(success = false)
        attempt.join()

        assertEquals(0, host.importCount)
        assertTrue(host.browserLoginFailure?.second is GetLineAuthException.Protocol)
        // The failure path must not paint entry state over the waiting attempt.
        assertFalse(host.refreshedEntry)
    }

    @Test
    fun deepLinkHandoffWhileBusy_defersAndImportsExactlyOnce() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.AwaitingDeepLink
        val launched = CompletableDeferred<Unit>()
        host.onLaunch = { launched.complete(Unit) }
        val flow = flow()

        val attempt = launch { flow.signIn(AuthMethod.Google) }
        launched.await()

        // Session appears, but the browser attempt still owns busy: the handoff
        // must defer the step rather than run a second import.
        store.saveSession(nativeSession())
        flow.onDeepLinkHandoff(success = true)
        flow.onDeepLinkHandoff(success = true)
        attempt.join()

        assertEquals(1, host.importCount)
    }

    @Test
    fun deepLinkHandoffWithNoAttemptInFlight_importsAndOwnsBusyItself() = runBlocking {
        store.saveSession(nativeSession())
        val flow = flow()

        flow.onDeepLinkHandoff(success = true)

        assertEquals(1, host.importCount)
        assertFalse(host.busyState)
        assertFalse(host.cancelableState)
    }

    @Test
    fun deepLinkHandoffFailureWithNoAttemptInFlight_returnsToProviders() = runBlocking {
        val flow = flow()

        flow.onDeepLinkHandoff(success = false)

        assertEquals(0, host.importCount)
        assertTrue(host.providersShown)
        assertEquals(GetLineProductState.AuthFailed, host.states.last())
    }

    // --- post-login step ownership ----------------------------------------

    @Test
    fun postLoginStep_isClaimedOnlyOncePerAttempt() = runBlocking {
        store.saveSession(nativeSession())
        val flow = flow()

        flow.runPostLoginStep()
        flow.runPostLoginStep()

        assertEquals(1, host.importCount)
    }

    @Test
    fun postLoginStepFailure_routesToTheSubscriptionRetryTarget() = runBlocking {
        store.saveSession(nativeSession())
        host.importError = GetLineAuthException.NoSubscription()
        val flow = flow()

        flow.runPostLoginStep()

        assertTrue(host.postLoginFailure is GetLineAuthException.NoSubscription)
        assertNull(host.browserLoginFailure)
        assertFalse(host.busyState)
    }

    // --- explicit cancel --------------------------------------------------

    @Test
    fun claimCancel_winsWhileTheAttemptIsStillUnclaimed() = runBlocking {
        pending.put(pendingAuth())

        assertTrue(flow().claimCancel())
        assertNull(pending.peek())
        assertTrue(
            pending.isCancellationMatching(callbackUri, setOf(AuthMethod.Google.name)),
        )
    }

    @Test
    fun claimCancel_losesAfterACallbackTookPending() = runBlocking {
        pending.put(pendingAuth())
        pending.takeIfMatches(callbackUri, AuthMethod.Google.name)

        assertFalse(flow().claimCancel())
    }

    @Test
    fun cancelableTracksTheClaimableWindow() = runBlocking {
        host.launchResult = BrowserAuthLaunchResult.Completed(
            android.net.Uri.parse("$callbackUri?code=one-time-code"),
        )
        val flow = flow()
        // Cancel is only offered once pending exists and before the callback lands.
        host.onLaunch = { assertTrue(flow.isCancelable) }

        assertFalse(flow.isCancelable)
        flow.signIn(AuthMethod.Google)
        assertFalse(flow.isCancelable)
    }

    // --- helpers ----------------------------------------------------------

    private fun nativeSession() = NativeSession(
        accessToken = "native-access",
        refreshToken = "native-refresh",
        expiresInSeconds = 3600L,
    )

    private fun pendingAuth() = PendingNativeAuth(
        provider = AuthMethod.Google.name,
        verifier = "verifier",
        callbackUri = callbackUri,
        createdAtMs = System.currentTimeMillis(),
        correlationId = "correlation",
    )

    private class FakeHost : BrowserAuthFlow.Host {
        var busyState = false
        var online = true
        var cancelableState = false
        var importCount = 0
        var importError: Exception? = null
        var providersShown = false
        var refreshedEntry = false
        var sessionEstablished: Boolean? = null
        var launchResult: BrowserAuthLaunchResult = BrowserAuthLaunchResult.NoBrowser
        var onLaunch: (() -> Unit)? = null
        var browserLoginFailure: Pair<AuthMethod, Exception>? = null
        var postLoginFailure: Exception? = null
        val states = mutableListOf<GetLineProductState>()
        val retryTargets = mutableListOf<String>()

        override val isBusy: Boolean
            get() = busyState

        override fun setBusy(busy: Boolean) {
            busyState = busy
        }

        override fun isOnline(): Boolean = online

        override fun markRetryBrowserLogin(method: AuthMethod) {
            retryTargets += "browser:${method.name}"
        }

        override fun markRetryRefresh() {
            retryTargets += "refresh"
        }

        override fun markRetryImportPreferred() {
            retryTargets += "import_preferred"
        }

        override suspend fun setProductState(state: GetLineProductState) {
            states += state
        }

        override suspend fun setSessionEstablished(established: Boolean) {
            sessionEstablished = established
        }

        override suspend fun showProviders() {
            providersShown = true
        }

        override suspend fun refreshEntryState() {
            refreshedEntry = true
            retryTargets += "refresh"
            providersShown = true
            states += GetLineProductState.NoProfile
        }

        override fun setCancelable(cancelable: Boolean) {
            cancelableState = cancelable
        }

        override fun authFailureState(): GetLineProductState =
            if (online) GetLineProductState.AuthFailed else GetLineProductState.Offline

        override suspend fun launchBrowser(
            method: AuthMethod,
            authUrl: String,
        ): BrowserAuthLaunchResult {
            onLaunch?.invoke()
            return launchResult
        }

        override suspend fun importPreferredSubscription() {
            importCount++
            importError?.let { throw it }
        }

        override suspend fun applyBrowserLoginFailure(method: AuthMethod, error: Exception) {
            browserLoginFailure = method to error
            states += authFailureState()
        }

        override suspend fun applyPostLoginFailure(error: Exception) {
            postLoginFailure = error
        }
    }

    private class FakeAuthApi : GetLineAuthApi {
        val calls = mutableListOf<String>()
        var failNativeExchange = false

        override suspend fun startBrowserAuth(
            method: AuthMethod,
            codeChallenge: String,
            appRedirect: String,
        ): BrowserAuthStartResponse {
            calls += "start"
            return BrowserAuthStartResponse(authUrl = "https://auth.example/start")
        }

        override suspend fun exchangeNativeCode(
            code: String,
            codeVerifier: String,
        ): NativeSession {
            calls += "native_exchange"
            if (failNativeExchange) {
                throw GetLineAuthException.HttpFailure(400, "invalid_grant")
            }
            return NativeSession(
                accessToken = "native-access",
                refreshToken = "native-refresh",
                expiresInSeconds = 3600L,
            )
        }

        override suspend fun sendEmailOtp(email: String): EmailOtpSendResult =
            throw UnsupportedOperationException()

        override suspend fun verifyEmailOtp(
            email: String,
            code: String,
        ): EmailOtpVerifyResult = throw UnsupportedOperationException()

        override suspend fun getCurrentUser(webToken: String): CurrentUser =
            CurrentUser(null, null, null, null, null)

        override suspend fun generateDeviceKey(webToken: String): DeviceKey {
            calls += "generate"
            return DeviceKey("one-time-key")
        }

        override suspend fun exchangeDeviceKey(deviceKey: String): NativeSession {
            calls += "exchange"
            return NativeSession(
                accessToken = "native-access",
                refreshToken = "native-refresh",
                expiresInSeconds = 3600L,
            )
        }

        override suspend fun refresh(refreshToken: String): NativeSession =
            throw UnsupportedOperationException()

        override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse =
            throw UnsupportedOperationException()

        override suspend fun getDashboard(accessToken: String): DashboardInfo =
            throw UnsupportedOperationException("login must not call dashboard")

        override suspend fun activateTrial(accessToken: String): Unit =
            throw UnsupportedOperationException("login must not activate trial")
    }
}
