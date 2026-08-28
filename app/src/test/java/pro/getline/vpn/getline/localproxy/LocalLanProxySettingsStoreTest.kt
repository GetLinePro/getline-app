package pro.getline.vpn.getline.localproxy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The store holds a live credential pair, so what matters here is who gets to
 * read it back: the same owner, and nobody else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalLanProxySettingsStoreTest {
    private lateinit var context: Context

    private var owner: String? = "owner-a"

    private val prefsName = "local_proxy_test"

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun store(): LocalLanProxySettingsStore =
        LocalLanProxySettingsStore(context, { owner }) { prefs() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs().edit { clear() }
        owner = "owner-a"
    }

    @Test
    fun firstUseGeneratesAValidConfig() {
        val config = store().loadOrCreate()!!

        assertNull(LocalLanProxyConfigValidator.validate(config))
        assertEquals("getline", config.username)
        assertEquals(20, config.password.length)
        assertTrue(config.port >= LocalLanProxyConfigValidator.MIN_PORT)
    }

    @Test
    fun generatedSettingsAreStable() {
        val first = store().loadOrCreate()

        assertEquals(first, store().loadOrCreate())
    }

    @Test
    fun savedSettingsSurviveANewInstance() {
        store().loadOrCreate()

        val edited = LocalLanProxyUserConfig(port = 4321, username = "someone", password = "another-secret")
        assertTrue(store().save(edited))

        assertEquals(edited, store().loadOrCreate())
    }

    @Test
    fun anotherOwnerNeverReadsTheStoredCredentials() {
        val mine = store().loadOrCreate()!!

        owner = "owner-b"
        val theirs = store().loadOrCreate()!!

        assertNotEquals(mine.password, theirs.password)
    }

    @Test
    fun anotherOwnerReadingSettingsDoesNotEraseTheMismatch() {
        val mine = store().loadOrCreate()!!

        owner = "owner-b"
        store().loadOrCreate()
        store().save(LocalLanProxyUserConfig(4321, "someone", "another-secret"))

        // The departed owner's record is still there and still recognised as
        // foreign: reconcileOwner() has not run yet, and a plain read must not
        // stand in for it — that is what would leave their listener up.
        assertTrue(store().belongsToAnotherOwner())

        owner = "owner-a"
        assertEquals(mine, store().loadOrCreate())
    }

    @Test
    fun signedOutInstallGetsItsOwnRecord() {
        owner = null
        val anonymous = store().loadOrCreate()!!

        owner = "owner-a"
        assertNotEquals(anonymous.password, store().loadOrCreate()!!.password)
    }

    @Test
    fun aStoredRecordThatNoLongerValidatesIsReplaced() {
        val stored = store().loadOrCreate()!!

        // The current owner's own key, so this really does corrupt the record
        // being read back — a bare "port" would just be junk from another
        // format and leave the record intact.
        prefs().edit { putInt("owner-a/port", 80) }
        assertEquals(80, prefs().getInt("owner-a/port", 0))

        val recovered = store().loadOrCreate()!!

        assertNull(LocalLanProxyConfigValidator.validate(recovered))
        assertNotEquals(80, recovered.port)
        assertNotEquals(stored.password, recovered.password)
    }

    @Test
    fun ownershipQuestionsAreAnsweredByTheStore() {
        assertFalse("no record is nothing to clean up", store().belongsToAnotherOwner())

        store().loadOrCreate()
        assertFalse("the current owner's own record", store().belongsToAnotherOwner())

        owner = "owner-b"
        assertTrue(store().belongsToAnotherOwner())
    }

    @Test
    fun discardRemovesOnlyForeignRecords() {
        val mine = store().loadOrCreate()!!

        // Still the same owner: a failed logout must not destroy these.
        assertTrue(store().discardForeignRecord())
        assertEquals(mine, store().loadOrCreate())

        owner = "owner-b"
        val theirs = store().loadOrCreate()!!

        assertTrue(store().discardForeignRecord())
        assertFalse(store().belongsToAnotherOwner())
        // The current owner keeps exactly what they had.
        assertEquals(theirs, store().loadOrCreate())

        owner = "owner-a"
        assertNotEquals(mine.password, store().loadOrCreate()!!.password)
    }

    @Test
    fun keysFromAnUnknownFormatAreTreatedAsSomethingToClear() {
        store().loadOrCreate()
        prefs().edit { putString("owner", "owner-a") }

        assertTrue(store().belongsToAnotherOwner())
        assertTrue(store().discardForeignRecord())
        assertFalse(prefs().contains("owner"))
    }

    @Test
    fun aWriteThatDidNotReachDiskIsReportedAsAFailure() {
        val failing = LocalLanProxySettingsStore(context, { owner }) { FailingCommitPreferences() }

        // Generation reports failure rather than handing back credentials that
        // exist only in memory — enabling with those would authenticate until
        // the process died and then stop, with nothing to show the user.
        assertNull(failing.loadOrCreate())
        assertFalse(failing.save(LocalLanProxyUserConfig(4321, "someone", "another-secret")))
    }

    @Test
    fun unusableStorageIsReportedRatherThanDegraded() {
        val broken = LocalLanProxySettingsStore(context, { owner }) {
            throw IllegalStateException("keystore unavailable")
        }

        assertFalse(broken.available)
        assertNull(broken.loadOrCreate())
        assertFalse(broken.save(LocalLanProxyUserConfig(4321, "someone", "another-secret")))
        // An unreadable store is also an unerasable one, and says so.
        assertFalse(broken.belongsToAnotherOwner())
        assertFalse(broken.discardForeignRecord())
    }
}

/**
 * Commits that fail. `SharedPreferences.commit()` returning false is what a
 * full or broken store looks like from here, and the wrapper that hides it
 * (`androidx`'s `edit(commit = true)`) is exactly what this guards against.
 */
private class FailingCommitPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FailingEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    private class FailingEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
        override fun remove(key: String?): SharedPreferences.Editor = this
        override fun clear(): SharedPreferences.Editor = this
        override fun commit(): Boolean = false
        override fun apply() = Unit
    }
}
