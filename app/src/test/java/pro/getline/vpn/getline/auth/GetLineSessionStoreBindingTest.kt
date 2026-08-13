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
        assertTrue(
            LinkOnlyBindingPolicy.isLinkOnlyBinding(
                store.managedProfileUuid,
                store.managedProfileSource,
                store.subscriptionId,
            ),
        )
    }

    @Test
    fun commitCreatedImportUuid_usesCommitNotApply() {
        val app = RuntimeEnvironment.getApplication()
        val recording = RecordingPreferences(
            app.getSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED, Context.MODE_PRIVATE),
        )
        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = { recording },
            encryptedStorageResetter = {
                app.deleteSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED)
            },
        )
        store.clearAccountState()
        store.savePendingImport(
            PendingImport(name = "GetLine", source = "https://pending.example/sub"),
        )
        val applyBefore = recording.applyCalls
        val commitBefore = recording.commitCalls

        assertTrue(store.commitCreatedImportUuid("created-uuid"))

        assertEquals(commitBefore + 1, recording.commitCalls)
        assertEquals(applyBefore, recording.applyCalls)
        assertEquals("created-uuid", store.pendingImport()?.reuseUuid)
    }

    @Test
    fun commitCreatedImportUuid_falseCommit_isNotSuccess() {
        val app = RuntimeEnvironment.getApplication()
        val recording = RecordingPreferences(
            app.getSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED, Context.MODE_PRIVATE),
        )
        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = { recording },
            encryptedStorageResetter = {
                app.deleteSharedPreferences(GetLineSessionStore.PREFS_FILE_ENCRYPTED)
            },
        )
        store.clearAccountState()
        store.savePendingImport(
            PendingImport(name = "GetLine", source = "https://pending.example/sub"),
        )
        recording.commitResult = false
        val commitBefore = recording.commitCalls

        assertFalse(store.commitCreatedImportUuid("created-uuid"))
        assertEquals(commitBefore + 1, recording.commitCalls)
    }

    @Test
    fun pendingImport_persistsPreviousManagedUuidToDelete() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
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
    fun pendingProfileCleanup_keepsMultipleUuids_andClearsOnlyExpectedUuid() {
        val store = testSessionStore(RuntimeEnvironment.getApplication())
        store.clearAccountState()
        store.rememberPendingProfileCleanup("old-profile-uuid")
        store.rememberPendingProfileCleanup("newer-old-profile-uuid")
        store.savePendingImport(
            PendingImport(
                name = "GetLine",
                source = "https://account.example.com/sub",
            ),
        )

        store.clearPendingImport()
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
}

/**
 * Counts [SharedPreferences.Editor.commit] vs [SharedPreferences.Editor.apply].
 * Reading the same prefs instance after apply() is not a durability check.
 */
private class RecordingPreferences(
    private val inner: android.content.SharedPreferences,
) : android.content.SharedPreferences by inner {
    var commitCalls = 0
    var applyCalls = 0
    var commitResult: Boolean? = null

    override fun edit(): android.content.SharedPreferences.Editor = RecordingEditor(inner.edit())

    private inner class RecordingEditor(
        private val inner: android.content.SharedPreferences.Editor,
    ) : android.content.SharedPreferences.Editor {
        override fun putString(
            key: String?,
            value: String?,
        ): android.content.SharedPreferences.Editor {
            inner.putString(key, value)
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): android.content.SharedPreferences.Editor {
            inner.putStringSet(key, values)
            return this
        }

        override fun putInt(key: String?, value: Int): android.content.SharedPreferences.Editor {
            inner.putInt(key, value)
            return this
        }

        override fun putLong(key: String?, value: Long): android.content.SharedPreferences.Editor {
            inner.putLong(key, value)
            return this
        }

        override fun putFloat(key: String?, value: Float): android.content.SharedPreferences.Editor {
            inner.putFloat(key, value)
            return this
        }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): android.content.SharedPreferences.Editor {
            inner.putBoolean(key, value)
            return this
        }

        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            inner.remove(key)
            return this
        }

        override fun clear(): android.content.SharedPreferences.Editor {
            inner.clear()
            return this
        }

        override fun commit(): Boolean {
            commitCalls += 1
            val written = inner.commit()
            return commitResult ?: written
        }

        override fun apply() {
            applyCalls += 1
            inner.apply()
        }
    }
}
