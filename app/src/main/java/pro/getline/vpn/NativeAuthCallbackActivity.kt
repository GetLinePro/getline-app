package pro.getline.vpn

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.github.kr328.clash.common.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pro.getline.vpn.getline.auth.AuthCallbackParser
import pro.getline.vpn.getline.auth.AuthCallbackResult
import pro.getline.vpn.getline.auth.AuthMethod
import pro.getline.vpn.getline.auth.GetLineAuthException
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.PendingNativeAuth
import pro.getline.vpn.getline.auth.PendingNativeAuthStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi

/**
 * Exported deep-link receiver for package-id callbacks
 * (`${applicationId}:/oauth2redirect`).
 *
 * Dual payload:
 * - `?code=` + pending verifier → native exchange (Google/Telegram, Custom Tab / VIEW)
 * - `?auth_token=` → web-token establish (Telegram / edge page)
 *
 * Auth Tab may also deliver a package-scheme VIEW in parallel with ActivityResult;
 * if the sibling path already established a session, this activity only hands off.
 */
class NativeAuthCallbackActivity : Activity() {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        scope.launch {
            val outcome = runCatching { completeCallback() }
                .getOrElse { error ->
                    Log.w("native_auth_callback failed kind=${error.javaClass.simpleName}")
                    // Sibling Auth Tab may already have saved a session.
                    runCatching {
                        GetLineSessionStore(this@NativeAuthCallbackActivity).hasRefreshToken()
                    }.getOrDefault(false)
                }
            handoffToOnboarding(success = outcome)
            finish()
        }
    }

    /** @return true when a native session exists after this call. */
    private suspend fun completeCallback(): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) {
            throw GetLineAuthException.InvalidCallback("Unexpected action")
        }
        val uri = intent?.data
            ?: throw GetLineAuthException.InvalidCallback("Missing callback URI")

        val parsed = AuthCallbackParser.parse(uri)
        val store = GetLineSessionStore(this)
        val repository = GetLineSessionRepository(
            api = RwpGetLineAuthApi(),
            store = store,
        )
        val pendingStore = PendingNativeAuthStore(this)

        when (parsed) {
            is AuthCallbackResult.NativeCode -> {
                val pending = takeNativeCodePending(pendingStore, uri.toString())
                if (pending == null) {
                    if (store.hasRefreshToken()) {
                        Log.i("native_auth_callback_ok kind=code already_session")
                        return true
                    }
                    throw GetLineAuthException.InvalidCallback("No matching pending auth")
                }
                establishNative(repository, pendingStore, pending, parsed.code)
            }
            is AuthCallbackResult.WebToken -> {
                val pending = pendingStore.takeIfMatches(
                    callbackUri = uri.toString(),
                    provider = AuthMethod.Telegram.name,
                )
                if (pending == null) {
                    if (store.hasRefreshToken()) {
                        Log.i("native_auth_callback_ok kind=web_token already_session")
                        return true
                    }
                    throw GetLineAuthException.InvalidCallback("No matching pending auth")
                }
                establishWeb(repository, pendingStore, pending, parsed.authToken)
            }
        }
        return true
    }

    private suspend fun establishNative(
        repository: GetLineSessionRepository,
        pendingStore: PendingNativeAuthStore,
        pending: PendingNativeAuth,
        code: String,
    ) {
        try {
            repository.establishFromNativeCode(code, pending.verifier)
        } catch (e: Exception) {
            // Allow one retry within TTL without reopening the browser.
            pendingStore.put(pending)
            throw e
        }
        Log.i(
            "native_auth_callback_ok kind=code provider=${pending.provider} " +
                "correlation=${pending.correlationId}",
        )
    }

    private suspend fun establishWeb(
        repository: GetLineSessionRepository,
        pendingStore: PendingNativeAuthStore,
        pending: PendingNativeAuth,
        webToken: String,
    ) {
        try {
            repository.establishFromWebToken(webToken)
        } catch (e: Exception) {
            pendingStore.put(pending)
            throw e
        }
        Log.i(
            "native_auth_callback_ok kind=web_token provider=${pending.provider} " +
                "correlation=${pending.correlationId}",
        )
    }

    private fun handoffToOnboarding(success: Boolean) {
        startActivity(
            Intent(this, GetLineOnboardingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(GetLineOnboardingActivity.EXTRA_NATIVE_AUTH_SUCCESS, success)
                putExtra(GetLineOnboardingActivity.EXTRA_NATIVE_AUTH_HANDLED, true)
            },
        )
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        job.cancel()
        super.onDestroy()
    }
}

/**
 * Accept native-code callbacks only for providers using the app-owned PKCE flow.
 * Peek first so an unknown provider never gets consumed by a generic callback.
 */
internal fun takeNativeCodePending(
    pendingStore: PendingNativeAuthStore,
    callbackUri: String,
): PendingNativeAuth? {
    val pending = pendingStore.peek() ?: return null
    if (pending.provider != AuthMethod.Google.name &&
        pending.provider != AuthMethod.Telegram.name
    ) {
        return null
    }
    return pendingStore.takeIfMatches(
        callbackUri = callbackUri,
        provider = pending.provider,
    )
}
