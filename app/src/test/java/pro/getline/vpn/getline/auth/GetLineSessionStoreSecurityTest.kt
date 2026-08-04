package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GetLineSessionStoreSecurityTest {
    @Test
    @Config(sdk = [23])
    fun api23_legacyFallbackDeletion_clearsCachedPreferencesWithoutNewApiCall() {
        val app = RuntimeEnvironment.getApplication()
        val cachedLegacy = legacyPrefs(app)
        cachedLegacy.edit()
            .putString("access_token", "legacy-access")
            .putString("refresh_token", "legacy-refresh")
            .commit()

        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = {
                app.getSharedPreferences("test_api23_secure_session", Context.MODE_PRIVATE)
            },
            encryptedStorageResetter = { },
        )

        assertTrue(cachedLegacy.all.isEmpty())
        assertFalse(store.otherPrefsFileExists())
    }

    @Test
    fun encryptedOpenFailure_resetsOnce_reopensEncrypted_andDeletesLegacyFallback() {
        val app = RuntimeEnvironment.getApplication()
        legacyPrefs(app).edit()
            .putString("access_token", "legacy-access")
            .putString("refresh_token", "legacy-refresh")
            .commit()
        var opens = 0
        var resets = 0

        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = {
                opens++
                if (opens == 1) error("broken keystore")
                app.getSharedPreferences("test_recovered_secure_session", Context.MODE_PRIVATE)
            },
            encryptedStorageResetter = { resets++ },
        )

        assertTrue(store.recoveredFromStorageFailure)
        assertEquals(GetLineSessionStore.BACKEND_ENCRYPTED, store.backendName)
        assertEquals(2, opens)
        assertEquals(1, resets)
        assertTrue(legacyPrefs(app).all.isEmpty())
        assertFalse(store.otherPrefsFileExists())
    }

    @Test
    fun lazyEncryptedValueFailure_isDetectedDuringOpen_andRecovered() {
        val app = RuntimeEnvironment.getApplication()
        val broken = app.getSharedPreferences("test_corrupt_secure_session", Context.MODE_PRIVATE)
        val recovered = app.getSharedPreferences("test_validated_secure_session", Context.MODE_PRIVATE)
        var opens = 0
        var resets = 0

        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = {
                opens++
                if (opens == 1) ReadFailPreferences(broken) else recovered
            },
            encryptedStorageResetter = { resets++ },
        )

        assertTrue(store.recoveredFromStorageFailure)
        assertEquals(2, opens)
        assertEquals(1, resets)
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun encryptedReopenFailure_failsClosed_withSanitizedException() {
        val app = RuntimeEnvironment.getApplication()
        legacyPrefs(app).edit().putString("refresh_token", "legacy-refresh").commit()
        var opens = 0
        var resets = 0

        val error = try {
            GetLineSessionStore(
                context = app,
                encryptedPrefsFactory = {
                    opens++
                    error("provider detail with secret")
                },
                encryptedStorageResetter = { resets++ },
            )
            fail("Expected GetLineSessionStorageException")
            null
        } catch (expected: GetLineSessionStorageException) {
            expected
        }

        assertEquals("Secure session storage unavailable", error?.message)
        assertNull(error?.cause)
        assertEquals(2, opens)
        assertEquals(1, resets)
        assertTrue(legacyPrefs(app).all.isEmpty())
    }

    @Test
    fun failedSessionCommit_discardsAmbiguousWrite_andReopensEmptyStore() {
        val app = RuntimeEnvironment.getApplication()
        val failedBacking = app.getSharedPreferences("test_failed_session", Context.MODE_PRIVATE)
        val recoveredBacking = app.getSharedPreferences("test_reopened_session", Context.MODE_PRIVATE)
        var opens = 0
        var resets = 0
        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = {
                opens++
                if (opens == 1) CommitFalsePreferences(failedBacking) else recoveredBacking
            },
            encryptedStorageResetter = {
                resets++
                app.deleteSharedPreferences("test_failed_session")
            },
        )

        try {
            store.saveSession(
                NativeSession(
                    accessToken = "new-access",
                    refreshToken = "new-refresh",
                    expiresInSeconds = 60L,
                ),
                nowMs = 1_000L,
            )
            fail("Expected GetLineSessionStorageException")
        } catch (_: GetLineSessionStorageException) {
            // Expected: callers must stop the auth attempt and ask for retry.
        }

        assertTrue(store.recoveredFromStorageFailure)
        assertEquals(2, opens)
        assertEquals(1, resets)
        assertTrue(
            app.getSharedPreferences("test_failed_session", Context.MODE_PRIVATE).all.isEmpty(),
        )
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun logout_clearsEncryptedAccountState_andLegacyFallback() {
        val app = RuntimeEnvironment.getApplication()
        val encrypted = app.getSharedPreferences("test_logout_secure_session", Context.MODE_PRIVATE)
        val store = testStore(app, encrypted)
        store.saveSession(
            NativeSession(
                accessToken = "access",
                refreshToken = "refresh",
                expiresInSeconds = 60L,
            ),
        )
        store.customerId = "customer"
        legacyPrefs(app).edit()
            .putString("access_token", "stranded-access")
            .putString("refresh_token", "stranded-refresh")
            .commit()

        store.clearAccountState()

        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertNull(store.customerId)
        assertTrue(legacyPrefs(app).all.isEmpty())
        assertFalse(store.otherPrefsFileExists())
    }

    @Test
    fun legacyDeletionFailure_afterLogout_doesNotCrashOrRestoreCredentials() {
        val app = RuntimeEnvironment.getApplication()
        val encrypted = app.getSharedPreferences(
            "test_logout_legacy_delete_failure",
            Context.MODE_PRIVATE,
        )
        var deletionAttempts = 0
        val store = GetLineSessionStore(
            context = app,
            encryptedPrefsFactory = { encrypted },
            encryptedStorageResetter = { },
            prefsDeleter = { _, _ ->
                deletionAttempts++
                if (deletionAttempts > 1) error("legacy file is busy")
            },
        )
        store.saveSession(NativeSession("access", "refresh", 60L))

        store.clearAccountState()

        assertEquals(2, deletionAttempts)
        assertNull(store.accessToken)
        assertNull(store.refreshToken)
        assertFalse(store.hasRefreshToken())
    }

    @Test
    fun sessionDiscardAlsoDeletesLegacyFallback_withoutLosingManagedBinding() {
        val app = RuntimeEnvironment.getApplication()
        val encrypted = app.getSharedPreferences("test_discard_secure_session", Context.MODE_PRIVATE)
        val store = testStore(app, encrypted)
        store.saveSession(NativeSession("access", "refresh", 60L))
        store.managedProfileUuid = "profile"
        store.managedProfileSource = "https://subscription.example/test"
        legacyPrefs(app).edit().putString("refresh_token", "stranded-refresh").commit()

        store.clearSessionKeepingBinding()

        assertFalse(store.hasRefreshToken())
        assertEquals("profile", store.managedProfileUuid)
        assertEquals("https://subscription.example/test", store.managedProfileSource)
        assertTrue(legacyPrefs(app).all.isEmpty())
    }

    private fun testStore(
        app: Context,
        encrypted: SharedPreferences,
    ): GetLineSessionStore = GetLineSessionStore(
        context = app,
        encryptedPrefsFactory = { encrypted },
        encryptedStorageResetter = { },
    )

    private fun legacyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(
            GetLineSessionStore.PREFS_FILE_FALLBACK,
            Context.MODE_PRIVATE,
        )

    /**
     * Simulates the ambiguous platform case: values reached the backing editor,
     * but commit reported failure. Production recovery must delete that file.
     */
    private class CommitFalsePreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun edit(): SharedPreferences.Editor = CommitFalseEditor(delegate.edit())
    }

    /** EncryptedSharedPreferences can defer value decryption until getAll/getString. */
    private class ReadFailPreferences(
        private val delegate: SharedPreferences,
    ) : SharedPreferences by delegate {
        override fun getAll(): MutableMap<String, *> = error("corrupt encrypted value")
    }

    private class CommitFalseEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            delegate.putString(key, value)
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            delegate.putStringSet(key, values)
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            delegate.putInt(key, value)
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            delegate.putLong(key, value)
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            delegate.putFloat(key, value)
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            delegate.putBoolean(key, value)
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            delegate.remove(key)
        }

        override fun clear(): SharedPreferences.Editor = apply {
            delegate.clear()
        }

        override fun commit(): Boolean {
            delegate.commit()
            return false
        }

        override fun apply() {
            delegate.apply()
        }
    }
}
