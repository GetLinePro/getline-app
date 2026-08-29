package pro.getline.vpn.getline.localproxy

import android.content.Context
import android.content.SharedPreferences
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

    /**
     * True when the store holds settings written by someone other than the
     * current owner. False for "nothing stored" and for "only the current
     * owner's settings" alike: neither is something to clean up.
     *
     * It stays true until [discardForeignRecord] succeeds, including after the
     * new owner has read or written settings of their own — that is what keeps
     * a reconcile that has not happened yet from being silently cancelled.
     */
    fun belongsToAnotherOwner(): Boolean

    /**
     * Best effort removal of everything that is not the current owner's.
     * Returns false when something remained — including when the store itself
     * is unusable, since an unreadable store is also an unerasable one.
     */
    fun discardForeignRecord(): Boolean
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
 * And the owner UUID as anything a caller may see: records are keyed by
 * whoever [owner] reports, so one owner's settings are never readable — nor
 * overwritable — by another. The store answers ownership questions itself, and
 * no caller compares UUIDs (see plan Decisions).
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
            read(prefs, ownerKey())?.let { return it }

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

    override fun belongsToAnotherOwner(): Boolean {
        val prefs = prefs ?: return false

        synchronized(lock) {
            return foreignKeys(prefs).isNotEmpty()
        }
    }

    override fun discardForeignRecord(): Boolean {
        val prefs = prefs ?: return false

        synchronized(lock) {
            // Re-read under the lock: ownership that moved back between the
            // check and here must not lose its own settings.
            val foreign = foreignKeys(prefs)
            if (foreign.isEmpty()) return true

            return try {
                val editor = prefs.edit()
                foreign.forEach(editor::remove)

                editor.commit()
            } catch (e: Exception) {
                Log.w("Local proxy settings discard failed: ${e.javaClass.simpleName}", e)

                false
            }
        }
    }

    /**
     * Every stored key that is not this owner's, including anything left by a
     * format this build no longer writes. Nothing here is read — only removed —
     * so an unrecognised key is junk to clear, never a value to trust.
     */
    private fun foreignKeys(prefs: SharedPreferences): List<String> {
        val mine = ownerKey()

        return prefs.all.keys.filter { key ->
            key.substringBefore(KEY_SEPARATOR, missingDelimiterValue = "") != mine
        }
    }

    private fun read(prefs: SharedPreferences, owner: String): LocalLanProxyUserConfig? {
        // One in-memory snapshot, so the three values cannot come from two
        // different states of the file.
        val values = prefs.all

        val port = values[key(owner, KEY_PORT)] as? Int ?: return null
        val username = values[key(owner, KEY_USERNAME)] as? String ?: return null
        val password = values[key(owner, KEY_PASSWORD)] as? String ?: return null

        val config = LocalLanProxyUserConfig(port, username, password)

        // A record that no longer validates (a downgraded rule, a corrupted
        // value) is replaced rather than offered: enabling with it would fail
        // in the native processor with nothing useful to say on screen.
        return if (LocalLanProxyConfigValidator.validate(config) == null) config else null
    }

    private fun write(prefs: SharedPreferences, config: LocalLanProxyUserConfig): Boolean {
        val owner = ownerKey()

        return try {
            // commit(), and its result is the answer: a listener enabled with
            // credentials that never reached the disk would keep working until
            // the process dies and then stop authenticating with no
            // explanation. androidx's edit(commit = true) discards this
            // boolean, which is why it is not used here.
            prefs.edit()
                .putInt(key(owner, KEY_PORT), config.port)
                .putString(key(owner, KEY_USERNAME), config.username)
                .putString(key(owner, KEY_PASSWORD), config.password)
                .commit()
        } catch (e: Exception) {
            // Never log the config: it carries the password.
            Log.w("Local proxy settings write failed: ${e.javaClass.simpleName}", e)

            false
        }
    }

    private fun key(owner: String, field: String): String = "$owner$KEY_SEPARATOR$field"

    /**
     * No managed owner is itself an owner — a link-only install can use the
     * proxy — so it gets a stable sentinel rather than being refused. Signing
     * in later changes the key, and the record generated while signed out
     * stays where it is until `reconcileOwner()` clears it.
     */
    private fun ownerKey(): String = owner() ?: OWNER_NONE

    private companion object {
        const val PREFS_FILE = "getline_local_proxy"

        /**
         * Records are keyed per owner rather than stamped with one. A stamped
         * single record has to be *replaced* the moment a different owner asks
         * for settings, which quietly erases the very mismatch
         * `reconcileOwner()` exists to act on — the departed owner's listener
         * would then never be told to close. Keyed records let a new owner get
         * their own settings without touching anyone else's, so the mismatch
         * survives until it is reconciled deliberately.
         *
         * Keys are encrypted at rest like values (AES256_SIV), so this does not
         * expose which accounts have used the app.
         */
        const val KEY_SEPARATOR = "/"

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
