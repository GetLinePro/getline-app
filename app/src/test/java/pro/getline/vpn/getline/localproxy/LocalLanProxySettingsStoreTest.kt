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

        // And the first owner does not get the replacement back either: the
        // record now belongs to whoever generated it last.
        owner = "owner-a"
        assertNotEquals(theirs.password, store().loadOrCreate()!!.password)
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
        store().loadOrCreate()
        prefs().edit { putInt("port", 80) }

        val recovered = store().loadOrCreate()!!

        assertNull(LocalLanProxyConfigValidator.validate(recovered))
        assertNotEquals(80, recovered.port)
    }

    @Test
    fun unusableStorageIsReportedRatherThanDegraded() {
        val broken = LocalLanProxySettingsStore(context, { owner }) {
            throw IllegalStateException("keystore unavailable")
        }

        assertFalse(broken.available)
        assertNull(broken.loadOrCreate())
        assertFalse(broken.save(LocalLanProxyUserConfig(4321, "someone", "another-secret")))
    }
}
