package pro.getline.vpn.getline.refresh

import android.util.Log
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ManagedProfileRefreshTest {
    private val managedUuid = "11111111-1111-1111-1111-111111111111"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setMinimumLoggingLevel(Log.DEBUG)
                .setExecutor(SynchronousExecutor())
                .build(),
        )
    }

    @Test
    fun missingBinding_isNoOp() = runBlocking {
        val updated = mutableListOf<UUID>()

        val outcome = refreshManagedProfile(null) { updated.add(it) }

        assertEquals(ManagedProfileRefreshOutcome.NoBinding, outcome)
        assertTrue(updated.isEmpty())
    }

    @Test
    fun blankOrInvalidBinding_isNoOp() = runBlocking {
        val updated = mutableListOf<UUID>()

        assertEquals(
            ManagedProfileRefreshOutcome.NoBinding,
            refreshManagedProfile("  ") { updated.add(it) },
        )
        assertEquals(
            ManagedProfileRefreshOutcome.NoBinding,
            refreshManagedProfile("not-a-uuid") { updated.add(it) },
        )
        assertTrue(updated.isEmpty())
    }

    @Test
    fun presentBinding_updatesExactlyThatUuid() = runBlocking {
        val updated = mutableListOf<UUID>()

        val outcome = refreshManagedProfile(managedUuid) { updated.add(it) }

        assertEquals(ManagedProfileRefreshOutcome.Updated, outcome)
        assertEquals(listOf(UUID.fromString(managedUuid)), updated)
    }

    @Test
    fun ensure_enqueuesUniquePeriodicWork() {
        val context = RuntimeEnvironment.getApplication()
        ManagedProfileRefresh.ensure(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ManagedProfileRefresh.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun cancel_removesWork() {
        val context = RuntimeEnvironment.getApplication()
        ManagedProfileRefresh.ensure(context)
        ManagedProfileRefresh.cancel(context)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ManagedProfileRefresh.UNIQUE_WORK_NAME)
            .get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished })
    }

    @Test
    fun rememberManagedProfile_ensuresWork() {
        val context = RuntimeEnvironment.getApplication()
        val sessions = GetLineSessionRepository(UnusedAuthApi(), testSessionStore(context))

        sessions.rememberManagedProfile(managedUuid)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ManagedProfileRefresh.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
    }

    @Test
    fun logout_cancelsWork() {
        val context = RuntimeEnvironment.getApplication()
        val store = testSessionStore(context)
        val sessions = GetLineSessionRepository(UnusedAuthApi(), store)
        sessions.rememberManagedProfile(managedUuid)

        sessions.logout()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ManagedProfileRefresh.UNIQUE_WORK_NAME)
            .get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished })
    }

    @Test
    fun discardSessionKeepingBinding_doesNotCancelWork() {
        val context = RuntimeEnvironment.getApplication()
        val store = seededStore(context)
        val sessions = GetLineSessionRepository(UnusedAuthApi(), store)
        sessions.rememberManagedProfile(managedUuid)

        sessions.discardSessionKeepingSubscription()

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(ManagedProfileRefresh.UNIQUE_WORK_NAME)
            .get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(managedUuid, sessions.managedProfileUuid())
    }

    private fun seededStore(context: android.content.Context): GetLineSessionStore {
        val store = testSessionStore(context)
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400L,
            ),
        )
        return store
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
