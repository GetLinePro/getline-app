package pro.getline.vpn.getline

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImportTerminalBindingTest {
    private lateinit var store: GetLineSessionStore
    private lateinit var sessions: GetLineSessionRepository

    private val draft = GetLineSubscriptionDraft(
        type = GetLineSubscriptionType.Url,
        name = "GetLine",
        source = "https://example.test/sub",
    )

    @Before
    fun setUp() {
        store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        sessions = GetLineSessionRepository(UnusedAuthApi(), store)
    }

    @Test
    fun recordCreatedUuid_isTheResumeReuseId_andDoesNotBind() {
        seedPending(reuseId = null)

        assertTrue(ImportTerminalBinding.recordCreatedUuid(sessions, "created-uuid"))

        assertEquals("created-uuid", sessions.pendingImportReuseId()?.value)
        assertNull(sessions.managedProfileUuid())
        assertTrue(sessions.hasPendingImport())
        assertEquals("https://example.test/sub", sessions.pendingImport()?.source)
        assertEquals("sub-to-remember", sessions.pendingImportSubscriptionIdToRemember())
        assertEquals("old-link", sessions.pendingImportPreviousManagedUuidToDelete())
    }

    @Test
    fun recordCreatedUuid_withoutPending_isNotSuccess() {
        assertFalse(ImportTerminalBinding.recordCreatedUuid(sessions, "created-uuid"))
        assertNull(sessions.pendingImportReuseId())
    }

    @Test
    fun commitSuccess_bindsAndClearsPending() {
        seedPending(reuseId = null)

        ImportTerminalBinding.commit(
            sessions = sessions,
            result = GetLineImportCoordinator.ImportTerminal.Success(
                GetLineSubscriptionId("created-uuid"),
            ),
            source = draft.source,
        )

        assertEquals("created-uuid", sessions.managedProfileUuid())
        assertEquals(draft.source, sessions.managedProfileSource())
        assertEquals("sub-to-remember", sessions.rememberedSubscriptionId())
        assertFalse(sessions.hasPendingImport())
        assertEquals(setOf("old-link"), sessions.pendingProfileCleanupUuids())
    }

    @Test
    fun binderDied_keepsPending() {
        seedPending(reuseId = "already-created")

        ImportTerminalBinding.commit(
            sessions = sessions,
            result = GetLineImportCoordinator.ImportTerminal.Unavailable(
                ImportUnavailableKind.BINDER_DIED,
                "kind=binder_died",
            ),
            source = draft.source,
        )

        assertTrue(sessions.hasPendingImport())
        assertEquals("already-created", sessions.pendingImportReuseId()?.value)
        assertNull(sessions.managedProfileUuid())
    }

    @Test
    fun backendUnavailable_clearsPending() {
        seedPending(reuseId = null)

        ImportTerminalBinding.commit(
            sessions = sessions,
            result = GetLineImportCoordinator.ImportTerminal.Unavailable(
                ImportUnavailableKind.BACKEND,
                "kind=backend",
            ),
            source = draft.source,
        )

        assertFalse(sessions.hasPendingImport())
    }

    private fun seedPending(reuseId: String?) {
        sessions.savePendingImport(
            draft = draft,
            reuseId = reuseId?.let { GetLineSubscriptionId(it) },
            subscriptionIdToRemember = "sub-to-remember",
            previousManagedUuidToDelete = "old-link",
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
