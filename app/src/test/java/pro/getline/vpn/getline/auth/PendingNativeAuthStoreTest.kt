package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.getline.vpn.AppEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingNativeAuthStoreTest {
    private fun store() = PendingNativeAuthStore.testStore(RuntimeEnvironment.getApplication())

    private fun sample(
        createdAtMs: Long = System.currentTimeMillis(),
        callbackUri: String = AppEnvironment.nativeCallbackUri,
    ) = PendingNativeAuth(
        provider = "Google",
        verifier = "verifier-value",
        callbackUri = callbackUri,
        createdAtMs = createdAtMs,
        correlationId = "corr-1",
    )

    @Test
    fun put_and_takeIfMatches_success() {
        val store = store()
        store.put(sample())
        val taken = store.takeIfMatches(AppEnvironment.nativeCallbackUri + "?code=x")
        assertNotNull(taken)
        assertEquals("verifier-value", taken!!.verifier)
        assertNull(store.peek())
    }

    @Test
    fun takeIfMatches_rejectsUriMismatch() {
        val store = store()
        store.put(sample())
        assertNull(store.takeIfMatches("other.scheme:/oauth2redirect?code=x"))
        assertNotNull(store.peek())
    }

    @Test
    fun takeIfMatches_rejectsExpired() {
        val store = store()
        store.put(sample(createdAtMs = System.currentTimeMillis() - PendingNativeAuth.TTL_MS - 1))
        assertNull(store.takeIfMatches(AppEnvironment.nativeCallbackUri + "?code=x"))
        assertNull(store.peek())
    }

    @Test
    fun put_replacesPrevious() {
        val store = store()
        store.put(sample().copy(verifier = "first", correlationId = "a"))
        store.put(sample().copy(verifier = "second", correlationId = "b"))
        val taken = store.takeIfMatches(AppEnvironment.nativeCallbackUri)
        assertEquals("second", taken!!.verifier)
        assertEquals("b", taken.correlationId)
    }

    @Test
    fun takeIfMatches_secondCallFails() {
        val store = store()
        store.put(sample())
        assertNotNull(store.takeIfMatches(AppEnvironment.nativeCallbackUri))
        assertNull(store.takeIfMatches(AppEnvironment.nativeCallbackUri))
    }

    @Test
    fun clear_removesPending() {
        val store = store()
        store.put(sample())
        store.clear()
        assertNull(store.peek())
    }

    @Test
    fun takeIfMatches_rejectsProviderMismatch_withoutClearing() {
        val store = store()
        store.put(sample()) // Google
        assertNull(
            store.takeIfMatches(
                AppEnvironment.nativeCallbackUri + "?auth_token=x",
                provider = "Telegram",
            ),
        )
        assertNotNull(store.peek())
        assertNotNull(
            store.takeIfMatches(
                AppEnvironment.nativeCallbackUri + "?code=x",
                provider = "Google",
            ),
        )
    }
}
