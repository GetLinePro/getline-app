package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * At most one active native OAuth attempt.
 *
 * Encrypted prefs (same MasterKey scheme as [GetLineSessionStore]) so the
 * PKCE verifier is not readable from a rooted backup of the plaintext file.
 *
 * Pending claim and explicit cancel are process-serialized via [lock] so Auth Tab,
 * the deep-link receiver and the UI cannot own the same attempt.
 */
class PendingNativeAuthStore internal constructor(
    context: Context,
    prefsFactory: (Context) -> SharedPreferences,
) {
    constructor(context: Context) : this(
        context = context,
        prefsFactory = ::createEncryptedPrefs,
    )

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = prefsFactory(appContext)

    fun put(pending: PendingNativeAuth) {
        synchronized(lock) {
            write(pending)
        }
    }

    fun peek(): PendingNativeAuth? = synchronized(lock) { read() }

    /**
     * Atomically read and clear when [callbackUri] matches the stored base URI
     * (scheme + path, no query), the attempt is not expired, and optional
     * [provider] matches (null = any).
     *
     * Mismatch → null without clearing. Expiry → clear and null.
     */
    fun takeIfMatches(
        callbackUri: String,
        provider: String? = null,
    ): PendingNativeAuth? {
        synchronized(lock) {
            val pending = read() ?: return null
            if (pending.isExpired()) {
                clearPendingLocked()
                return null
            }
            if (!callbackBasesMatch(pending.callbackUri, callbackUri)) {
                return null
            }
            if (provider != null &&
                !provider.equals(pending.provider, ignoreCase = true)
            ) {
                return null
            }
            clearPendingLocked()
            return pending
        }
    }

    fun clear() {
        synchronized(lock) {
            clearLocked()
        }
    }

    /** Remove only the active verifier; keep any cancelled-predecessor fence. */
    fun clearPending() {
        synchronized(lock) {
            clearPendingLocked()
        }
    }

    /**
     * Atomically abandon the still-unclaimed attempt.
     *
     * A callback that already consumed pending wins the race and returns false.
     * A successful cancel removes the PKCE verifier and keeps only a non-secret,
     * TTL-bounded marker so that this attempt's late callback can finish silently.
     */
    fun cancelPending(): Boolean {
        synchronized(lock) {
            val pending = read() ?: return false
            prefs.edit {
                removePendingKeys()
                putString(KEY_CANCELLED_PROVIDER, pending.provider)
                putString(KEY_CANCELLED_CALLBACK_URI, pending.callbackUri)
                putLong(KEY_CANCELLED_AT, System.currentTimeMillis())
            }
            return true
        }
    }

    /** Match an explicit-cancel marker; unsolicited callbacks stay errors. */
    fun isCancellationMatching(
        callbackUri: String,
        allowedProviders: Set<String>,
    ): Boolean {
        synchronized(lock) {
            val cancelled = readCancellation() ?: return false
            if (System.currentTimeMillis() - cancelled.cancelledAtMs > PendingNativeAuth.TTL_MS) {
                clearCancellationLocked()
                return false
            }
            if (!callbackBasesMatch(cancelled.callbackUri, callbackUri) ||
                cancelled.provider !in allowedProviders
            ) {
                return false
            }
            return true
        }
    }

    private fun write(pending: PendingNativeAuth) {
        prefs.edit {
            removePendingKeys()
            putString(KEY_PROVIDER, pending.provider)
            putString(KEY_VERIFIER, pending.verifier)
            putString(KEY_CALLBACK_URI, pending.callbackUri)
            putLong(KEY_CREATED_AT, pending.createdAtMs)
            putString(KEY_CORRELATION_ID, pending.correlationId)
        }
    }

    private fun clearLocked() {
        prefs.edit { clear() }
    }

    private fun clearPendingLocked() {
        prefs.edit { removePendingKeys() }
    }

    fun clearCancellation() {
        synchronized(lock) {
            clearCancellationLocked()
        }
    }

    private fun clearCancellationLocked() {
        prefs.edit {
            remove(KEY_CANCELLED_PROVIDER)
            remove(KEY_CANCELLED_CALLBACK_URI)
            remove(KEY_CANCELLED_AT)
        }
    }

    private fun SharedPreferences.Editor.removePendingKeys() {
        remove(KEY_PROVIDER)
        remove(KEY_VERIFIER)
        remove(KEY_CALLBACK_URI)
        remove(KEY_CREATED_AT)
        remove(KEY_CORRELATION_ID)
    }

    private fun read(): PendingNativeAuth? {
        val provider = prefs.getString(KEY_PROVIDER, null) ?: return null
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return null
        val callbackUri = prefs.getString(KEY_CALLBACK_URI, null) ?: return null
        val correlationId = prefs.getString(KEY_CORRELATION_ID, null) ?: return null
        if (!prefs.contains(KEY_CREATED_AT)) return null
        val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)
        if (provider.isBlank() || verifier.isBlank() || callbackUri.isBlank()) {
            return null
        }
        return PendingNativeAuth(
            provider = provider,
            verifier = verifier,
            callbackUri = callbackUri,
            createdAtMs = createdAt,
            correlationId = correlationId,
        )
    }

    private fun readCancellation(): CancelledNativeAuth? {
        val provider = prefs.getString(KEY_CANCELLED_PROVIDER, null) ?: return null
        val callbackUri = prefs.getString(KEY_CANCELLED_CALLBACK_URI, null) ?: return null
        if (!prefs.contains(KEY_CANCELLED_AT)) return null
        if (provider.isBlank() || callbackUri.isBlank()) return null
        return CancelledNativeAuth(
            provider = provider,
            callbackUri = callbackUri,
            cancelledAtMs = prefs.getLong(KEY_CANCELLED_AT, 0L),
        )
    }

    private data class CancelledNativeAuth(
        val provider: String,
        val callbackUri: String,
        val cancelledAtMs: Long,
    )

    companion object {
        const val PREFS_FILE = "getline_pending_native_auth"

        /** Process-wide lock shared by all store instances. */
        private val lock = Any()

        private const val KEY_PROVIDER = "provider"
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_CALLBACK_URI = "callback_uri"
        private const val KEY_CREATED_AT = "created_at_ms"
        private const val KEY_CORRELATION_ID = "correlation_id"
        private const val KEY_CANCELLED_PROVIDER = "cancelled_provider"
        private const val KEY_CANCELLED_CALLBACK_URI = "cancelled_callback_uri"
        private const val KEY_CANCELLED_AT = "cancelled_at_ms"

        /**
         * Compare base callback URIs: ignore query/fragment; require same
         * scheme and path (private-use `scheme:/oauth2redirect` form).
         */
        fun callbackBasesMatch(expected: String, actual: String): Boolean {
            val e = android.net.Uri.parse(expected.trim())
            val a = android.net.Uri.parse(actual.trim())
            val eScheme = e.scheme?.lowercase() ?: return false
            val aScheme = a.scheme?.lowercase() ?: return false
            if (eScheme != aScheme) return false
            val ePath = e.path.orEmpty().ifEmpty { "/" }
            val aPath = a.path.orEmpty().ifEmpty { "/" }
            return ePath == aPath
        }

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        /** Robolectric / unit tests without AndroidKeyStore. */
        internal fun testStore(context: Context): PendingNativeAuthStore =
            PendingNativeAuthStore(context) {
                it.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            }
    }
}
