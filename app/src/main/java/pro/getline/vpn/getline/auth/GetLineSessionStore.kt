package pro.getline.vpn.getline.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists native session material and GetLine-managed VPN binding.
 * Never stores web auth_token, device_key, callback URIs, or OAuth codes.
 *
 * Binding keys ([subscriptionId], [managedProfileUuid], [managedProfileSource],
 * [customerId]) survive process death and normal APK updates.
 * Full clear: [clearAccountState]. Session-only clear keeping managed binding:
 * [clearSessionKeepingBinding].
 */
class GetLineSessionStore(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context.applicationContext)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_ACCESS_TOKEN, value) }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_REFRESH_TOKEN, value) }

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

    var customerId: String?
        get() = prefs.getString(KEY_CUSTOMER_ID, null)
        set(value) = prefs.edit { putString(KEY_CUSTOMER_ID, value) }

    fun hasRefreshToken(): Boolean = !refreshToken.isNullOrBlank()

    fun saveSession(session: NativeSession, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit {
            putString(KEY_ACCESS_TOKEN, session.accessToken)
            putString(KEY_REFRESH_TOKEN, session.refreshToken)
            putLong(
                KEY_ACCESS_EXPIRES_AT,
                nowMs + session.expiresInSeconds.coerceAtLeast(0L) * 1000L,
            )
        }
    }

    /**
     * In-flight product import. Survives Activity destroy and process death so
     * HOME/relaunch can resume instead of dead-ending on a cancelled coroutine.
     */
    fun savePendingImport(pending: PendingImport) {
        prefs.edit {
            putString(KEY_PENDING_IMPORT_NAME, pending.name)
            putString(KEY_PENDING_IMPORT_SOURCE, pending.source)
            putString(KEY_PENDING_IMPORT_TYPE, pending.typeName)
            putString(KEY_PENDING_IMPORT_REUSE_UUID, pending.reuseUuid)
            putString(KEY_PENDING_IMPORT_SUBSCRIPTION_ID, pending.subscriptionIdToRemember)
            putLong(KEY_PENDING_IMPORT_INTERVAL, pending.interval)
            putString(
                KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID,
                pending.previousManagedUuidToDelete,
            )
        }
    }

    fun clearPendingImport() {
        prefs.edit {
            remove(KEY_PENDING_IMPORT_NAME)
            remove(KEY_PENDING_IMPORT_SOURCE)
            remove(KEY_PENDING_IMPORT_TYPE)
            remove(KEY_PENDING_IMPORT_REUSE_UUID)
            remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
            remove(KEY_PENDING_IMPORT_INTERVAL)
            remove(KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID)
        }
    }

    fun pendingImport(): PendingImport? {
        val source = prefs.getString(KEY_PENDING_IMPORT_SOURCE, null)?.takeIf { it.isNotBlank() }
            ?: return null
        val name = prefs.getString(KEY_PENDING_IMPORT_NAME, null)?.takeIf { it.isNotBlank() }
            ?: "GetLine"
        return PendingImport(
            name = name,
            source = source,
            typeName = prefs.getString(KEY_PENDING_IMPORT_TYPE, null) ?: "Url",
            reuseUuid = prefs.getString(KEY_PENDING_IMPORT_REUSE_UUID, null)?.takeIf { it.isNotBlank() },
            subscriptionIdToRemember = prefs.getString(KEY_PENDING_IMPORT_SUBSCRIPTION_ID, null)
                ?.takeIf { it.isNotBlank() },
            interval = prefs.getLong(KEY_PENDING_IMPORT_INTERVAL, 0L),
            previousManagedUuidToDelete = prefs.getString(
                KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID,
                null,
            )?.takeIf { it.isNotBlank() },
        )
    }

    fun hasPendingImport(): Boolean = pendingImport() != null

    fun clearAccountState() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_SUBSCRIPTION_ID)
            remove(KEY_PROFILE_UUID)
            remove(KEY_PROFILE_SOURCE)
            remove(KEY_CUSTOMER_ID)
            remove(KEY_PENDING_IMPORT_NAME)
            remove(KEY_PENDING_IMPORT_SOURCE)
            remove(KEY_PENDING_IMPORT_TYPE)
            remove(KEY_PENDING_IMPORT_REUSE_UUID)
            remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
            remove(KEY_PENDING_IMPORT_INTERVAL)
            remove(KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID)
        }
    }

    /**
     * Drop the account session but keep the link-only managed binding.
     * Used when the user declines to replace a link-imported subscription:
     * the profile stays theirs to refresh and remove.
     */
    fun clearSessionKeepingBinding() {
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_CUSTOMER_ID)
            remove(KEY_SUBSCRIPTION_ID)
            // pending import keys: this login is abandoned
            remove(KEY_PENDING_IMPORT_NAME)
            remove(KEY_PENDING_IMPORT_SOURCE)
            remove(KEY_PENDING_IMPORT_TYPE)
            remove(KEY_PENDING_IMPORT_REUSE_UUID)
            remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
            remove(KEY_PENDING_IMPORT_INTERVAL)
            remove(KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID)
        }
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
        prefs.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_ACCESS_EXPIRES_AT)
            remove(KEY_CUSTOMER_ID)
            if (!keepLikelyLinkOnlySource) {
                remove(KEY_PROFILE_SOURCE)
            }
            remove(KEY_PENDING_IMPORT_NAME)
            remove(KEY_PENDING_IMPORT_SOURCE)
            remove(KEY_PENDING_IMPORT_TYPE)
            remove(KEY_PENDING_IMPORT_REUSE_UUID)
            remove(KEY_PENDING_IMPORT_SUBSCRIPTION_ID)
            remove(KEY_PENDING_IMPORT_INTERVAL)
            remove(KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID)
        }
    }

    fun isAccessTokenValid(nowMs: Long = System.currentTimeMillis(), skewMs: Long = 60_000L): Boolean {
        val token = accessToken
        if (token.isNullOrBlank()) return false
        val expiresAt = accessTokenExpiresAtEpochMs
        if (expiresAt <= 0L) return false
        return nowMs + skewMs < expiresAt
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Fall back so auth remains usable if keystore is unavailable.
            context.getSharedPreferences(FILE_NAME_FALLBACK, Context.MODE_PRIVATE)
        }
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
        private const val KEY_CUSTOMER_ID = "customer_id"
        private const val KEY_PENDING_IMPORT_NAME = "pending_import_name"
        private const val KEY_PENDING_IMPORT_SOURCE = "pending_import_source"
        private const val KEY_PENDING_IMPORT_TYPE = "pending_import_type"
        private const val KEY_PENDING_IMPORT_REUSE_UUID = "pending_import_reuse_uuid"
        private const val KEY_PENDING_IMPORT_SUBSCRIPTION_ID = "pending_import_subscription_id"
        private const val KEY_PENDING_IMPORT_INTERVAL = "pending_import_interval"
        private const val KEY_PENDING_IMPORT_PREVIOUS_MANAGED_UUID =
            "pending_import_previous_managed_uuid"

        /** Pref file names — e2e / debug probes may look for either. */
        const val PREFS_FILE_ENCRYPTED = FILE_NAME
        const val PREFS_FILE_FALLBACK = FILE_NAME_FALLBACK
    }
}

/**
 * Durable in-flight import request (URL path only for product flows).
 * [typeName] is the [pro.getline.vpn.getline.GetLineSubscriptionType] name.
 * [interval] is the profile update interval in ms (ExternalControl may set it).
 */
data class PendingImport(
    val name: String,
    val source: String,
    val typeName: String = "Url",
    val reuseUuid: String? = null,
    val subscriptionIdToRemember: String? = null,
    val interval: Long = 0L,
    /** Link-only UUID to delete after UseAccount import Success. */
    val previousManagedUuidToDelete: String? = null,
)
