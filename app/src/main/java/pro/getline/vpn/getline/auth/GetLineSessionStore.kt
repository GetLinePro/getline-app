package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * Persists native session material and GetLine-managed VPN binding.
 * Never stores web auth_token, device_key, callback URIs, or OAuth codes.
 *
 * Binding keys ([subscriptionId], [managedProfileUuid], [managedProfileSource],
 * pending profile cleanup UUIDs, and [customerId] survive process death and normal APK updates.
 * Full clear: [clearAccountState]. Session-only clear keeping managed binding:
 * [clearSessionKeepingBinding].
 */
class GetLineSessionStore internal constructor(
    context: Context,
    private val encryptedPrefsFactory: (Context) -> SharedPreferences,
    private val encryptedStorageResetter: (Context) -> Unit,
    private val prefsDeleter: (Context, String) -> Unit = ::deletePrefs,
) {
    constructor(context: Context) : this(
        context = context,
        encryptedPrefsFactory = ::createEncryptedPrefs,
        encryptedStorageResetter = ::resetEncryptedStorage,
    )

    internal val appContext: Context = context.applicationContext
    private var prefs: SharedPreferences

    /** True only when this instance discarded an unreadable encrypted session. */
    var recoveredFromStorageFailure: Boolean = false
        private set

    init {
        deleteLegacySessionStores()
        prefs = try {
            openValidatedEncryptedStorage()
        } catch (_: Exception) {
            recoveredFromStorageFailure = true
            resetAndReopenEncryptedStorage()
        }
    }

    /**
     * Diagnostics only. A usable store is always encrypted; plaintext fallback
     * is legacy data that is deleted without being read.
     */
    val backendName: String
        get() = BACKEND_ENCRYPTED

    /**
     * Diagnostics only: a legacy plaintext session file still exists on disk.
     * A usable store should always report false because initialization deletes it.
     */
    fun otherPrefsFileExists(): Boolean = prefsFile(appContext, FILE_NAME_FALLBACK).exists()

    /**
     * Diagnostics only: non-keyset entries physically present in the backing file.
     *
     * The encrypted store looks entries up by their SIV-encrypted key name. If the
     * master keyset is ever replaced, the old entries stay on disk but can never be
     * addressed again — through [SharedPreferences] that is indistinguishable from
     * an empty store. A positive count next to null reads is that case.
     * `-1` when the file exists but cannot be parsed.
     */
    fun rawEntryCount(): Int {
        val file = prefsFile(appContext, FILE_NAME)
        if (!file.exists()) return 0
        return runCatching {
            PREF_ENTRY_NAME
                .findAll(file.readText())
                .count { !it.groupValues[1].startsWith(TINK_KEYSET_PREFIX) }
        }.getOrDefault(-1)
    }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        private set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

    var accessTokenExpiresAtEpochMs: Long
        get() = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_ACCESS_EXPIRES_AT, value) }

    var subscriptionId: String?
        get() = prefs.getString(KEY_SUBSCRIPTION_ID, null)
        set(value) = prefs.edit { putString(KEY_SUBSCRIPTION_ID, value) }

    /**
     * UUID of the CMFA profile owned by GetLine product flow (login or URL import).
     * Storage key kept stable (`profile_uuid`) for existing installs.
     */
    var managedProfileUuid: String?
        get() = prefs.getString(KEY_PROFILE_UUID, null)
        set(value) = prefs.edit { putString(KEY_PROFILE_UUID, value) }

    /**
     * Subscription URL used to provision the managed profile.
     * Enables remote repair for URL-import without a native session.
     */
    var managedProfileSource: String?
        get() = prefs.getString(KEY_PROFILE_SOURCE, null)
        set(value) = prefs.edit { putString(KEY_PROFILE_SOURCE, value) }

    /** Old managed profiles awaiting idempotent deletion after replacement imports. */
    fun pendingProfileCleanupUuids(): Set<String> =
        prefs.getStringSet(KEY_PENDING_PROFILE_CLEANUP_UUIDS, emptySet())
            .orEmpty()
            .filterTo(linkedSetOf()) { it.isNotBlank() }

    fun rememberPendingProfileCleanup(uuid: String) {
        val pending = uuid.takeIf { it.isNotBlank() } ?: return
        synchronized(PENDING_PROFILE_CLEANUP_LOCK) {
            prefs.edit {
                putStringSet(
                    KEY_PENDING_PROFILE_CLEANUP_UUIDS,
                    pendingProfileCleanupUuids() + pending,
                )
            }
        }
    }

    /** Remove only the completed UUID; other failed replacements stay owned. */
    fun clearPendingProfileCleanup(expectedUuid: String) {
        synchronized(PENDING_PROFILE_CLEANUP_LOCK) {
            val remaining = pendingProfileCleanupUuids() - expectedUuid
            prefs.edit {
                if (remaining.isEmpty()) {
                    remove(KEY_PENDING_PROFILE_CLEANUP_UUIDS)
                } else {
                    putStringSet(KEY_PENDING_PROFILE_CLEANUP_UUIDS, remaining)
                }
            }
        }
    }

    var customerId: String?
        get() = prefs.getString(KEY_CUSTOMER_ID, null)
        set(value) = prefs.edit { putString(KEY_CUSTOMER_ID, value) }

    fun hasRefreshToken(): Boolean = !refreshToken.isNullOrBlank()

    /** Read session and binding fields once, then infer their product meaning. */
    fun managedBindingSnapshot(): ManagedBindingSnapshot {
        // SharedPreferences.getAll() takes one synchronized in-memory snapshot.
        // Binding remains useful when only refresh-token decryption fails.
        val values = prefs.all
        val uuid = values[KEY_PROFILE_UUID] as? String
        val source = values[KEY_PROFILE_SOURCE] as? String
        val subscription = values[KEY_SUBSCRIPTION_ID] as? String
        val hasSession = !((values[KEY_REFRESH_TOKEN] as? String).isNullOrBlank())
        return ManagedBindingSnapshot.infer(
            hasSession = hasSession,
            managedProfileUuid = uuid,
            managedProfileSource = source,
            subscriptionId = subscription,
        )
    }

    fun saveSession(session: NativeSession, nowMs: Long = System.currentTimeMillis()) {
        val expiresAt = nowMs + session.expiresInSeconds.coerceAtLeast(0L) * 1000L
        try {
            val committed = prefs.edit()
                .putString(KEY_ACCESS_TOKEN, session.accessToken)
                .putString(KEY_REFRESH_TOKEN, session.refreshToken)
                .putLong(KEY_ACCESS_EXPIRES_AT, expiresAt)
                .commit()
            if (!committed ||
                prefs.getString(KEY_ACCESS_TOKEN, null) != session.accessToken ||
                prefs.getString(KEY_REFRESH_TOKEN, null) != session.refreshToken ||
                prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L) != expiresAt
            ) {
                throw SessionStorageWriteFailed()
            }
        } catch (_: Exception) {
            // SharedPreferences commits one complete file. If encryption or the
            // disk write failed, discard the store before reporting auth failure;
            // callers must never continue with an in-memory partial session.
            recoveredFromStorageFailure = true
            prefs = resetAndReopenEncryptedStorage()
            throw GetLineSessionStorageException()
        }
    }

    fun clearAccountState() {
        commitCleanupOrReset {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_SUBSCRIPTION_ID)
            remove(KEY_PROFILE_UUID)
            remove(KEY_PROFILE_SOURCE)
            remove(KEY_PENDING_PROFILE_CLEANUP_UUIDS)
            remove(KEY_CUSTOMER_ID)
            removeStalePendingImportKeys()
        }
        deleteLegacySessionStoresBestEffort()
    }

    /**
     * Drop the account session but keep the link-only managed binding.
     * Used when the user declines to replace a link-imported subscription:
     * the profile stays theirs to refresh and remove.
     */
    fun clearSessionKeepingBinding() {
        commitCleanupOrReset {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_CUSTOMER_ID)
            remove(KEY_SUBSCRIPTION_ID)
            removeStalePendingImportKeys()
        }
        deleteLegacySessionStoresBestEffort()
    }

    /**
     * Drop a rejected session while preserving the best-known binding shape.
     *
     * Shape is inferred from persisted fields, not an explicit provenance flag.
     * UUID + source + no subscription ID is treated as link-only, including the
     * brief post-login window before an account subscription ID is committed.
     * Otherwise the account UUID + subscription ID stay, but its URL is removed.
     */
    fun clearRejectedSessionKeepingBindingShape() {
        val keepLikelyLinkOnlySource =
            !managedProfileUuid.isNullOrBlank() &&
                !managedProfileSource.isNullOrBlank() &&
                subscriptionId.isNullOrBlank()
        commitCleanupOrReset {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_CUSTOMER_ID)
            if (!keepLikelyLinkOnlySource) {
                remove(KEY_PROFILE_SOURCE)
            }
            removeStalePendingImportKeys()
        }
        deleteLegacySessionStoresBestEffort()
    }

    fun isAccessTokenValid(nowMs: Long = System.currentTimeMillis(), skewMs: Long = 60_000L): Boolean {
        val token = accessToken
        if (token.isNullOrBlank()) return false
        val expiresAt = accessTokenExpiresAtEpochMs
        if (expiresAt <= 0L) return false
        return nowMs + skewMs < expiresAt
    }

    /** Drop ignored leftover keys from upgraded installs. Not a product API. */
    private fun SharedPreferences.Editor.removeStalePendingImportKeys() {
        remove(KEY_PENDING_IMPORT_NAME)
        remove(KEY_PENDING_IMPORT_SOURCE)
        remove(KEY_PENDING_IMPORT_TYPE)
        remove(KEY_PENDING_IMPORT_REUSE_UUID)
        remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
        remove(KEY_PENDING_IMPORT_INTERVAL)
        remove(KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID)
    }

    private fun commitCleanupOrReset(block: SharedPreferences.Editor.() -> Unit) {
        val committed = try {
            prefs.edit().apply(block).commit()
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            // Losing binding metadata is preferable to leaving credentials after
            // logout/rejection. The imported VPN profile lives in another store.
            recoveredFromStorageFailure = true
            prefs = resetAndReopenEncryptedStorage()
        }
    }

    private fun resetAndReopenEncryptedStorage(): SharedPreferences {
        try {
            encryptedStorageResetter(appContext)
            deleteLegacySessionStores()
            return openValidatedEncryptedStorage()
        } catch (_: Exception) {
            // Fixed text, no original exception/cause: crypto providers are not
            // allowed to leak values into auth logs or diagnostic reports.
            throw GetLineSessionStorageException()
        }
    }

    /** Force lazy key/value decryption now so corruption enters the recovery path. */
    private fun openValidatedEncryptedStorage(): SharedPreferences {
        val opened = encryptedPrefsFactory(appContext)
        opened.all
        return opened
    }

    private fun deleteLegacySessionStores() {
        for (name in LEGACY_SESSION_PREFS) {
            try {
                prefsDeleter(appContext, name)
            } catch (_: Exception) {
                throw GetLineSessionStorageException()
            }
            if (prefsFile(appContext, name).exists() ||
                prefsBackupFile(appContext, name).exists()
            ) {
                throw GetLineSessionStorageException()
            }
        }
    }

    /**
     * Credentials are already gone from the encrypted store at this point.
     * A stubborn legacy file must not crash logout or replace the original auth
     * failure; the next store open retries strict deletion and fails closed.
     */
    private fun deleteLegacySessionStoresBestEffort() {
        try {
            deleteLegacySessionStores()
        } catch (_: GetLineSessionStorageException) {
            // Retried strictly during the next GetLineSessionStore construction.
        }
    }

    /**
     * Diagnostics only: hours since the backing file was last written, `-1` if absent.
     * An APK update does not touch shared_prefs, so an age older than the install
     * places the last write — including a clear — before that update.
     */
    fun backingFileAgeHours(nowMs: Long = System.currentTimeMillis()): Long {
        val file = prefsFile(appContext, FILE_NAME)
        val modified = file.lastModified()
        if (modified <= 0L) return -1L
        return (nowMs - modified) / 3_600_000L
    }

    companion object {
        private const val FILE_NAME = "getline_native_session"
        private const val FILE_NAME_FALLBACK = "getline_native_session_fallback"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_PROFILE_UUID = "profile_uuid"
        private const val KEY_PROFILE_SOURCE = "profile_source"
        private const val KEY_PENDING_PROFILE_CLEANUP_UUIDS =
            "pending_profile_cleanup_uuids"
        private val PENDING_PROFILE_CLEANUP_LOCK = Any()
        private const val KEY_CUSTOMER_ID = "customer_id"
        private const val KEY_PENDING_IMPORT_NAME = "pending_import_name"
        private const val KEY_PENDING_IMPORT_SOURCE = "pending_import_source"
        private const val KEY_PENDING_IMPORT_TYPE = "pending_import_type"
        private const val KEY_PENDING_IMPORT_REUSE_UUID = "pending_import_reuse_uuid"
        private const val KEY_PENDING_IMPORT_SUBSCRIPTION_ID = "pending_import_subscription_id"
        private const val KEY_PENDING_IMPORT_INTERVAL = "pending_import_interval"
        private const val KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID =
            "pending_import_previous_managed_uuid"

        /** Pref file names — e2e/debug probes reject the legacy plaintext one. */
        const val PREFS_FILE_ENCRYPTED = FILE_NAME
        const val PREFS_FILE_FALLBACK = FILE_NAME_FALLBACK

        /** [backendName] token for GL-19 breadcrumbs. */
        const val BACKEND_ENCRYPTED = "enc"

        private val LEGACY_SESSION_PREFS = listOf(FILE_NAME_FALLBACK)

        private const val TINK_KEYSET_PREFIX = "__androidx_security_crypto_"
        private val PREF_ENTRY_NAME =
            Regex("""<(?:string|long|int|boolean|float|set)\s+name="([^"]*)"""")

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun resetEncryptedStorage(context: Context) {
            deletePrefs(context, FILE_NAME)
            if (prefsFile(context, FILE_NAME).exists() ||
                prefsBackupFile(context, FILE_NAME).exists()
            ) {
                throw GetLineSessionStorageException()
            }
            val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }

        /**
         * Context.deleteSharedPreferences is API 24. API 23 must first clear the
         * process-cached instance, then remove both disk copies.
         */
        private fun deletePrefs(context: Context, name: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(name)
                return
            }
            val cleared = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            if (!cleared) {
                throw GetLineSessionStorageException()
            }
            prefsFile(context, name).delete()
            prefsBackupFile(context, name).delete()
        }

        /** `dataDir` is API 24; `filesDir.parentFile` works on every supported API. */
        private fun prefsFile(context: Context, name: String): File =
            File(File(context.filesDir.parentFile, "shared_prefs"), "$name.xml")

        private fun prefsBackupFile(context: Context, name: String): File =
            File(prefsFile(context, name).path + ".bak")

        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}

/** Sanitized fail-closed signal; never carries crypto/provider messages. */
class GetLineSessionStorageException : Exception("Secure session storage unavailable")

/** Internal control-flow marker for a false SharedPreferences commit. */
private class SessionStorageWriteFailed : Exception()
