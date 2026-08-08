package pro.getline.vpn

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.PendingNativeAuth
import pro.getline.vpn.getline.auth.PendingNativeAuthStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NativeAuthCallbackGateTest {
    private fun store(): PendingNativeAuthStore =
        PendingNativeAuthStore.testStore(RuntimeEnvironment.getApplication()).also {
            it.clear()
        }

    private fun pending(provider: String) = PendingNativeAuth(
        provider = provider,
        verifier = "verifier-value",
        callbackUri = AppEnvironment.nativeCallbackUri,
        createdAtMs = System.currentTimeMillis(),
        correlationId = "corr-1",
    )

    @Test
    fun nativeCodePending_acceptsTelegram() {
        val store = store()
        store.put(pending(AuthMethod.Telegram.name))

        assertNotNull(
            takeNativeCodePending(
                store,
                AppEnvironment.nativeCallbackUri + "?code=one-time-code",
            ),
        )
        assertNull(store.peek())
    }

    @Test
    fun nativeCodePending_acceptsGoogle() {
        val store = store()
        store.put(pending(AuthMethod.Google.name))

        assertNotNull(takeNativeCodePending(store, AppEnvironment.nativeCallbackUri))
        assertNull(store.peek())
    }

    @Test
    fun nativeCodePending_rejectsWithoutPending() {
        assertNull(takeNativeCodePending(store(), AppEnvironment.nativeCallbackUri))
    }

    @Test
    fun nativeCodePending_rejectsForeignProviderWithoutConsuming() {
        val store = store()
        store.put(pending("Email"))

        assertNull(takeNativeCodePending(store, AppEnvironment.nativeCallbackUri))
        assertNotNull(store.peek())
    }
}
