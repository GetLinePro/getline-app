package pro.getline.vpn.getline.localproxy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.github.kr328.clash.common.log.Log
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * The settings the facade reads and writes. Narrow on purpose: the facade has
 * no business knowing where they live, and a test has no business needing a
 * keystore to exercise the facade's own logic.
 */
interface LocalLanProxySettings {
    /**
     * The settings for the current owner, generating them on first use.
     * Null only when the store is unusable.
     */
    fun loadOrCreate(): LocalLanProxyUserConfig?

    /** Persists [config] for the current owner. False when the store is unusable. */
    fun save(config: LocalLanProxyUserConfig): Boolean
}

/**
 * The local proxy's own settings, kept apart from every other store because
 * they contain a live credential pair.
 *
 * Encrypted prefs, same MasterKey scheme as `GetLineSessionStore` and
 * `PendingNativeAuthStore`, so the password is not readable from a rooted
 * backup of a plaintext file.
 *
 * Two things are deliberately *not* here. Enabled state: it belongs to the
 * running session and is re-derived from the runtime on every launch, so a
 * killed process can never come back claiming a listener that is not there.
 * And the owner UUID as anything a caller may see: the record is bound to
 * whoever [owner] reports at the time it is read, and settings saved under a
 * different owner are discarded rather than handed over — the store answers
 * ownership questions itself, and no caller compares UUIDs (see plan
 * Decisions).
 */
class LocalLanProxySettingsStore internal constructor(
    context: Context,
    private val owner: () -> String?,
    prefsFactory: (Context) -> SharedPreferences,
) : LocalLanProxySettings {
    constructor(context: Context, owner: () -> String?) : this(
        context = context,
        owner = owner,
        prefsFactory = ::createEncryptedPrefs,
    )

    private val appContext = context.applicationContext

    /**
     * Null when the encrypted store could not be opened at all. Kept as a
     * failure rather than silently degraded to plaintext: these are
     * credentials, and a proxy the user cannot enable is a better outcome than
     * one whose password sits in a readable file.
     */
    private val prefs: SharedPreferences? = try {
        prefsFactory(appContext)
    } catch (e: Exception) {
        Log.w("Local proxy settings store unavailable: ${e.javaClass.simpleName}", e)

        null
    }

    val available: Boolean
        get() = prefs != null

    /**
     * Generation on read is what makes the screen useful the first time it is
     * opened: the user gets a working port and credential pair to copy without
     * having to invent one, and the values are stable from then on.
     */
    override fun loadOrCreate(): LocalLanProxyUserConfig? {
        val prefs = prefs ?: return null

        synchronized(lock) {
            read(prefs)?.let { return it }

            val generated = LocalLanProxyUserConfig(
                port = chooseFreePort(),
                username = DEFAULT_USERNAME,
                password = generatePassword(),
            )

            return if (write(prefs, generated)) generated else null
        }
    }

    override fun save(config: LocalLanProxyUserConfig): Boolean {
        val prefs = prefs ?: return false

        synchronized(lock) {
            return write(prefs, config)
        }
    }

    private fun read(prefs: SharedPreferences): LocalLanProxyUserConfig? {
        // One in-memory snapshot, so the owner check and the values it guards
        // cannot come from two different states of the file.
        val values = prefs.all

        val storedOwner = values[KEY_OWNER] as? String ?: return null
        if (storedOwner != ownerKey()) {
            // Someone else's record. Reading it would hand this owner the
            // previous one's credentials; generating fresh settings overwrites
            // it in place, which is also what makes a replaced account start
            // from a new password rather than inherit one.
            return null
        }

        val port = values[KEY_PORT] as? Int ?: return null
        val username = values[KEY_USERNAME] as? String ?: return null
        val password = values[KEY_PASSWORD] as? String ?: return null

        val config = LocalLanProxyUserConfig(port, username, password)

        // A record that no longer validates (a downgraded rule, a corrupted
        // value) is replaced rather than offered: enabling with it would fail
        // in the native processor with nothing useful to say on screen.
        return if (LocalLanProxyConfigValidator.validate(config) == null) config else null
    }

    private fun write(prefs: SharedPreferences, config: LocalLanProxyUserConfig): Boolean {
        return try {
            prefs.edit(commit = true) {
                putString(KEY_OWNER, ownerKey())
                putInt(KEY_PORT, config.port)
                putString(KEY_USERNAME, config.username)
                putString(KEY_PASSWORD, config.password)
            }

            true
        } catch (e: Exception) {
            // Never log the config: it carries the password.
            Log.w("Local proxy settings write failed: ${e.javaClass.simpleName}", e)

            false
        }
    }

    /**
     * No managed owner is itself an owner — a link-only install can use the
     * proxy — so it gets a stable sentinel rather than being refused. Signing
     * in later changes the key, and the record generated while signed out is
     * left for `reconcileOwner()` instead of being adopted.
     */
    private fun ownerKey(): String = owner() ?: OWNER_NONE

    private companion object {
        const val PREFS_FILE = "getline_local_proxy"

        const val KEY_OWNER = "owner"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"

        const val OWNER_NONE = "none"

        const val DEFAULT_USERNAME = "getline"
        const val PASSWORD_LENGTH = 20

        /** URL-safe and unambiguous in a copied proxy URL: no `:`, `@`, `/` or quoting. */
        const val PASSWORD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        val lock = Any()

        val random = SecureRandom()

        fun generatePassword(): String {
            val alphabet = PASSWORD_ALPHABET

            return buildString(PASSWORD_LENGTH) {
                repeat(PASSWORD_LENGTH) {
                    append(alphabet[random.nextInt(alphabet.length)])
                }
            }
        }

        /**
         * Asks the OS for a free port instead of picking one: whatever it hands
         * out is free right now and outside the privileged range, which is a
         * better first guess than a constant every install would collide on.
         * It is not a reservation — the socket is closed immediately, and Enable
         * still preflights the port before touching anything.
         */
        fun chooseFreePort(): Int {
            return try {
                ServerSocket(0).use { it.localPort }
                    .takeIf { it in LocalLanProxyConfigValidator.MIN_PORT..LocalLanProxyConfigValidator.MAX_PORT }
                    ?: randomHighPort()
            } catch (e: Exception) {
                Log.w("Local proxy port probe failed: ${e.javaClass.simpleName}", e)

                randomHighPort()
            }
        }

        fun randomHighPort(): Int = 20000 + random.nextInt(20000)

        fun createEncryptedPrefs(context: Context): SharedPreferences {
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
    }
}
