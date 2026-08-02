package pro.getline.vpn.getline.auth

import android.content.Context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GetLineSessionStoreBindingTest {
    @Test
    fun clearSessionKeepingBinding_dropsTokensKeepsManagedBinding() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400L,
            ),
        )
        store.customerId = "cust-1"
        store.subscriptionId = "sub-should-clear"
        store.managedProfileUuid = "profile-uuid"
        store.managedProfileSource = "https://sub.example.com/link"
        store.savePendingImport(
            PendingImport(
                name = "GetLine",
                source = "https://pending",
                typeName = "Url",
                reuseUuid = "reuse",
                subscriptionIdToRemember = "pending-sub",
                interval = 0L,
                previousManagedUuidToDelete = "orphan-uuid",
            ),
        )

        store.clearSessionKeepingBinding()

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals(0L, store.accessTokenExpiresAtEpochMs)
        assertNull(store.customerId)
        assertNull(store.subscriptionId)
        assertFalse(store.hasRefreshToken())
        assertFalse(store.hasPendingImport())

        assertEquals("profile-uuid", store.managedProfileUuid)
        assertEquals("https://sub.example.com/link", store.managedProfileSource)
    }

    @Test
    fun clearRejectedSession_accountBindingKeepsIdentityButDropsRemoteSource() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400L,
            ),
        )
        store.customerId = "customer-id"
        store.subscriptionId = "subscription-id"
        store.managedProfileUuid = "profile-uuid"
        store.managedProfileSource = "https://account.example.com/sub"

        store.clearRejectedSessionKeepingBindingShape()

        assertFalse(store.hasRefreshToken())
        assertNull(store.customerId)
        assertEquals("subscription-id", store.subscriptionId)
        assertEquals("profile-uuid", store.managedProfileUuid)
        assertNull(store.managedProfileSource)
        assertFalse(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                store.managedProfileUuid,
                store.managedProfileSource,
                store.subscriptionId,
            ),
        )
    }

    @Test
    fun clearRejectedSession_linkOnlyBindingKeepsItsSource() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 86_400L,
            ),
        )
        store.managedProfileUuid = "profile-uuid"
        store.managedProfileSource = "https://link.example.com/sub"

        store.clearRejectedSessionKeepingBindingShape()

        assertFalse(store.hasRefreshToken())
        assertNull(store.subscriptionId)
        assertEquals("profile-uuid", store.managedProfileUuid)
        assertEquals("https://link.example.com/sub", store.managedProfileSource)
        assertTrue(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                store.managedProfileUuid,
                store.managedProfileSource,
                store.subscriptionId,
            ),
        )
    }

    @Test
    fun pendingImport_persistsPreviousManagedUuidToDelete() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.savePendingImport(
            PendingImport(
                name = "GetLine",
                source = "https://account.example.com/sub",
                typeName = "Url",
                reuseUuid = null,
                subscriptionIdToRemember = "sub-new",
                interval = 0L,
                previousManagedUuidToDelete = "old-link-only-uuid",
            ),
        )

        val pending = store.pendingImport()
        assertEquals("old-link-only-uuid", pending?.previousManagedUuidToDelete)
        assertEquals("https://account.example.com/sub", pending?.source)

        store.clearPendingImport()
        assertNull(store.pendingImport())
    }

    @Test
    fun clearAccountState_stillClearsBinding() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.managedProfileUuid = "profile-uuid"
        store.managedProfileSource = "https://sub.example.com/link"
        store.saveSession(
            NativeSession(
                accessToken = "a",
                refreshToken = "r",
                expiresInSeconds = 60L,
            ),
        )

        store.clearAccountState()

        assertNull(store.managedProfileUuid)
        assertNull(store.managedProfileSource)
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun discardSessionKeepingSubscription_repoDelegates() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.saveSession(
            NativeSession(
                accessToken = "a",
                refreshToken = "r",
                expiresInSeconds = 60L,
            ),
        )
        store.managedProfileUuid = "u"
        store.managedProfileSource = "https://s"
        val repo = GetLineSessionRepository(object : GetLineAuthApi {
            override suspend fun startBrowserAuth(method: AuthMethod) = error("n/a")
            override suspend fun sendEmailOtp(email: String) = error("n/a")
            override suspend fun verifyEmailOtp(email: String, code: String) = error("n/a")
            override suspend fun getCurrentUser(webToken: String) = error("n/a")
            override suspend fun generateDeviceKey(webToken: String) = error("n/a")
            override suspend fun exchangeDeviceKey(deviceKey: String) = error("n/a")
            override suspend fun refresh(refreshToken: String) = error("n/a")
            override suspend fun getSubscriptions(accessToken: String) = error("n/a")
            override suspend fun getDashboard(accessToken: String) = error("n/a")
        }, store)

        repo.discardSessionKeepingSubscription()

        assertFalse(repo.hasSession())
        assertEquals("u", repo.managedProfileUuid())
        assertEquals("https://s", repo.managedProfileSource())
        assertTrue(repo.canRemoteRepair())
    }

    /**
     * The forked-storage probe is only useful if it looks at the real pref path:
     * a wrong path reports "no fork" forever and hides the failure it exists for.
     */
    @Test
    fun otherPrefsFileExists_detectsTheFileTheStoreDidNotOpen() {
        val app = RuntimeEnvironment.getApplication()
        val store = GetLineSessionStore(app)
        assertFalse(store.otherPrefsFileExists())

        val other = if (store.backendName == GetLineSessionStore.BACKEND_ENCRYPTED) {
            GetLineSessionStore.PREFS_FILE_FALLBACK
        } else {
            GetLineSessionStore.PREFS_FILE_ENCRYPTED
        }
        app.getSharedPreferences(other, Context.MODE_PRIVATE)
            .edit()
            .putString("refresh_token", "stranded")
            .commit()

        assertTrue(store.otherPrefsFileExists())
    }

    /**
     * Counting must see real entries and ignore the Tink keysets, otherwise a
     * silently re-keyed store still reports "empty" and the probe proves nothing.
     */
    @Test
    fun rawEntryCount_countsStoredEntriesNotKeysets() {
        val store = GetLineSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        assertEquals(0, store.rawEntryCount())

        store.managedProfileUuid = "u"
        store.subscriptionId = "s"

        assertEquals(2, store.rawEntryCount())
    }
}
