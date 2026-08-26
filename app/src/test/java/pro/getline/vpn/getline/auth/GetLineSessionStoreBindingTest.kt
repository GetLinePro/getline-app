package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.SharedPreferences

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
    fun managedBindingSnapshot_readsStateFromOnePreferencesMap() {
        val app = RuntimeEnvironment.getApplication()
        val name = "test_snapshot_atomic"
        app.deleteSharedPreferences(name)
        app.getSharedPreferences(name, Context.MODE_PRIVATE)
            .edit()
            .putString("refresh_token", "refresh")
            .putString("profile_uuid", "profile-uuid")
            .putString("profile_source", "https://sub.example.com/link")
            .putString("subscription_id", "subscription-id")
            .commit()
        val counting = CountingPreferences(
            app.getSharedPreferences(name, Context.MODE_PRIVATE),
        )
        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = { counting },
            encryptedStorageResetter = { app.deleteSharedPreferences(name) },
        )
        counting.allReads = 0
        counting.getStringReads = 0

        val snapshot = store.managedBindingSnapshot()

        assertEquals(1, counting.allReads)
        assertEquals(0, counting.getStringReads)
        assertTrue(snapshot.hasSession)
        assertEquals("profile-uuid", snapshot.managedProfileUuid)
        assertEquals("https://sub.example.com/link", snapshot.managedProfileSource)
        assertEquals("subscription-id", snapshot.subscriptionId)
        assertEquals(ManagedBindingSnapshot.Provenance.AccountBound, snapshot.provenance)
    }

    @Test
    fun clearSessionKeepingBinding_dropsTokensKeepsManagedBinding() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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
        store.rememberPendingProfileCleanup("old-profile-uuid")

        store.clearSessionKeepingBinding()

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertEquals(0L, store.accessTokenExpiresAtEpochMs)
        assertNull(store.customerId)
        assertNull(store.subscriptionId)
        assertFalse(store.hasRefreshToken())

        assertEquals("profile-uuid", store.managedProfileUuid)
        assertEquals("https://sub.example.com/link", store.managedProfileSource)
        assertEquals(setOf("old-profile-uuid"), store.pendingProfileCleanupUuids())
    }

    @Test
    fun clearRejectedSession_accountBindingKeepsIdentityButDropsRemoteSource() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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
        assertEquals(
            ManagedBindingSnapshot.Provenance.AccountBound,
            store.managedBindingSnapshot().provenance,
        )
    }

    @Test
    fun clearRejectedSession_linkOnlyBindingKeepsItsSource() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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
        assertEquals(
            ManagedBindingSnapshot.Provenance.LinkOnly,
            store.managedBindingSnapshot().provenance,
        )
    }

    @Test
    fun pendingProfileCleanup_keepsMultipleUuids_andClearsOnlyExpectedUuid() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.rememberPendingProfileCleanup("old-profile-uuid")
        store.rememberPendingProfileCleanup("newer-old-profile-uuid")

        assertEquals(
            setOf("old-profile-uuid", "newer-old-profile-uuid"),
            store.pendingProfileCleanupUuids(),
        )

        store.clearPendingProfileCleanup("newer-attempt")
        assertEquals(
            setOf("old-profile-uuid", "newer-old-profile-uuid"),
            store.pendingProfileCleanupUuids(),
        )

        store.clearPendingProfileCleanup("old-profile-uuid")
        assertEquals(
            setOf("newer-old-profile-uuid"),
            store.pendingProfileCleanupUuids(),
        )
    }

    @Test
    fun clearAccountState_stillClearsBinding() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.managedProfileUuid = "profile-uuid"
        store.managedProfileSource = "https://sub.example.com/link"
        store.rememberPendingProfileCleanup("old-profile-uuid")
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
        assertTrue(store.pendingProfileCleanupUuids().isEmpty())
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun discardSessionKeepingSubscription_repoDelegates() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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
            override suspend fun startBrowserAuth(
                method: AuthMethod,
                codeChallenge: String,
                appRedirect: String,
            ) = error("n/a")

            override suspend fun exchangeNativeCode(
                code: String,
                codeVerifier: String,
            ) = error("n/a")

            override suspend fun sendEmailOtp(email: String) = error("n/a")
            override suspend fun verifyEmailOtp(email: String, code: String) = error("n/a")
            override suspend fun getCurrentUser(webToken: String) = error("n/a")
            override suspend fun generateDeviceKey(webToken: String) = error("n/a")
            override suspend fun exchangeDeviceKey(deviceKey: String) = error("n/a")
            override suspend fun refresh(refreshToken: String) = error("n/a")
            override suspend fun getSubscriptions(accessToken: String) = error("n/a")
            override suspend fun getDashboard(accessToken: String) = error("n/a")
            override suspend fun activateTrial(accessToken: String) = error("n/a")
        }, store)

        repo.discardSessionKeepingSubscription()

        assertFalse(repo.hasSession())
        assertEquals("u", repo.managedProfileUuid())
        assertEquals("https://s", repo.managedProfileSource())
        assertTrue(repo.canRemoteRepair())
    }

    /**
     * The legacy-storage probe is only useful if it looks at the real pref path:
     * a wrong path reports "clean" forever and hides stranded plaintext material.
     */
    @Test
    fun otherPrefsFileExists_detectsLegacyPlaintextFile() {
        val app = RuntimeEnvironment.getApplication()
        val store = testSessionStore(app)
        assertFalse(store.otherPrefsFileExists())

        val other = GetLineSessionStore.PREFS_FILE_FALLBACK
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
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        assertEquals(0, store.rawEntryCount())

        store.managedProfileUuid = "u"
        store.subscriptionId = "s"

        assertEquals(2, store.rawEntryCount())
    }

    private class CountingPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        var allReads = 0
        var getStringReads = 0

        override fun getAll(): MutableMap<String, *> {
            allReads++
            return delegate.all
        }

        override fun getString(key: String?, defValue: String?): String? {
            getStringReads++
            return delegate.getString(key, defValue)
        }
    }
}
