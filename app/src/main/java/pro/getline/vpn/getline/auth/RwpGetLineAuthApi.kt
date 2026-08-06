package pro.getline.vpn.getline.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.network.UnderlyingNetworkSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class RwpGetLineAuthApi(
    private val origin: String = AppEnvironment.apiOrigin,
    /**
     * Optional ConnectivityManager for tests / injection.
     * When null, resolves from [Global.application] at request time.
     */
    private val connectivityManager: ConnectivityManager? = null,
    /**
     * Connection factory. Substituted in tests to make the underlying-bind
     * failure reproducible without a device: the fallback below is the only
     * thing standing between a foreign lockdown VPN and a dead control plane,
     * and a real socket cannot be told to refuse the bind on demand.
     */
    private val openConnection: (URL, Network?) -> HttpURLConnection =
        ::openControlPlaneConnection,
) : GetLineAuthApi {
    init {
        // Fail closed before any network I/O if origin is wrong for this flavor.
        GetLineControlPlaneHostPolicy.requireApiOrigin(origin)
    }

    override suspend fun startBrowserAuth(
        method: AuthMethod,
        codeChallenge: String,
        appRedirect: String,
    ): BrowserAuthStartResponse {
        require(method.requiresBrowser()) {
            "AuthMethod.$method cannot start browser auth"
        }
        require(codeChallenge.isNotBlank()) { "code_challenge is blank" }
        require(appRedirect.isNotBlank()) { "app_redirect is blank" }
        val path = startPath(method, appRedirect, codeChallenge)
        val json = request(
            method = "GET",
            path = path,
            bearer = null,
            body = null,
            xhr = false,
            includeBrowserOriginHeaders = false,
        )
        val authUrl = json.optStringOrNull("auth_url")
            ?: throw GetLineAuthException.Protocol("auth_url missing")
        if (authUrl.isBlank()) {
            throw GetLineAuthException.Protocol("auth_url is blank")
        }
        // Reject wrong-environment / arbitrary hosts before browser launch.
        GetLineControlPlaneHostPolicy.requireBrowserLaunchUrl(authUrl)
        return BrowserAuthStartResponse(authUrl = authUrl)
    }

    override suspend fun exchangeNativeCode(
        code: String,
        codeVerifier: String,
    ): NativeSession {
        require(code.isNotBlank()) { "code is blank" }
        require(codeVerifier.isNotBlank()) { "code_verifier is blank" }
        val body = JSONObject()
            .put("code", code)
            .put("code_verifier", codeVerifier)
            .toString()
        val json = request(
            method = "POST",
            path = NATIVE_EXCHANGE_PATH,
            bearer = null,
            body = body,
            xhr = true,
            includeBrowserOriginHeaders = true,
        )
        return json.toNativeSession()
    }

    override suspend fun sendEmailOtp(email: String): EmailOtpSendResult {
        val body = emailOtpSendBody(email)
        val json = request(
            method = "POST",
            path = EMAIL_SEND_OTP_PATH,
            bearer = null,
            body = body,
            xhr = true,
            includeBrowserOriginHeaders = true,
            allowEmptyBody = true,
        )
        return parseEmailOtpSendResult(json)
    }

    override suspend fun verifyEmailOtp(email: String, code: String): EmailOtpVerifyResult {
        val body = emailOtpVerifyBody(email, code)
        val json = request(
            method = "POST",
            path = EMAIL_VERIFY_OTP_PATH,
            bearer = null,
            body = body,
            xhr = true,
            includeBrowserOriginHeaders = true,
            errorContext = AuthErrorContext.EmailOtpVerify,
        )
        return parseEmailOtpVerifyResult(json)
    }

    override suspend fun getCurrentUser(webToken: String): CurrentUser {
        val json = authorizedGet("/api/auth/me", webToken)
        return CurrentUser(
            customerId = json.optStringOrNull("customer_id"),
            username = json.optStringOrNull("username"),
            firstName = json.optStringOrNull("first_name"),
            telegramId = json.optStringOrNull("telegram_id")
                ?: json.optLongOrNull("telegram_id")?.toString(),
            role = json.optStringOrNull("role"),
        )
    }

    override suspend fun generateDeviceKey(webToken: String): DeviceKey {
        val json = authorizedGet(
            path = "/api/auth/device-key/generate",
            bearer = webToken,
            xhr = true,
        )
        val key = json.optStringOrNull("device_key")
            ?: throw GetLineAuthException.Protocol("device_key missing")
        return DeviceKey(key)
    }

    override suspend fun exchangeDeviceKey(deviceKey: String): NativeSession {
        val body = JSONObject().put("device_key", deviceKey).toString()
        val json = request(
            method = "POST",
            path = "/api/auth/device-key/exchange",
            bearer = null,
            body = body,
            xhr = true,
            includeBrowserOriginHeaders = true,
        )
        return json.toNativeSession()
    }

    override suspend fun refresh(refreshToken: String): NativeSession {
        val body = JSONObject().put("refresh_token", refreshToken).toString()
        val json = request(
            method = "POST",
            path = "/api/auth/native/refresh",
            bearer = null,
            body = body,
            xhr = true,
            includeBrowserOriginHeaders = true,
            errorContext = AuthErrorContext.NativeRefresh,
        )
        return json.toNativeSession()
    }

    override suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse {
        val json = authorizedGet("/api/subscriptions", accessToken)
        return SubscriptionsJson.parseResponse(json)
    }

    override suspend fun getDashboard(accessToken: String): DashboardInfo {
        val json = authorizedGet(DASHBOARD_PATH, accessToken)
        return parseDashboard(json)
    }

    override suspend fun activateTrial(accessToken: String) {
        request(
            method = "POST",
            path = ACTIVATE_TRIAL_PATH,
            bearer = accessToken,
            body = null,
            xhr = false,
            includeBrowserOriginHeaders = false,
            allowEmptyBody = true,
        )
    }

    private suspend fun authorizedGet(
        path: String,
        bearer: String,
        xhr: Boolean = false,
    ): JSONObject {
        return request(
            method = "GET",
            path = path,
            bearer = bearer,
            body = null,
            xhr = xhr,
            includeBrowserOriginHeaders = false,
        )
    }

    private suspend fun request(
        method: String,
        path: String,
        bearer: String?,
        body: String?,
        xhr: Boolean,
        includeBrowserOriginHeaders: Boolean,
        allowEmptyBody: Boolean = false,
        errorContext: AuthErrorContext = AuthErrorContext.Default,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$origin$path")

        fun open(network: Network?): HttpURLConnection {
            return openConnection(url, network).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                doInput = true
                // Never follow 3xx: a stage handler must not bounce the e2e client
                // onto production RWP/Auth (or any other host) with Bearer headers.
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (xhr) {
                    setRequestProperty("X-Requested-With", "XMLHttpRequest")
                }
                if (includeBrowserOriginHeaders) {
                    setRequestProperty("Origin", origin)
                    setRequestProperty("Referer", "$origin/")
                }
                if (bearer != null) {
                    setRequestProperty("Authorization", "Bearer $bearer")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        }

        // Prefer the underlying (non-VPN) network. When TunService is up with a
        // broken/expired outbound, default routing goes through fake-ip + proxy
        // and control-plane GETs fail even though the account/subscription is fine.
        val underlying = resolveUnderlyingNetwork()
        var connection = open(underlying)

        if (underlying != null) {
            // Seeing a network is not the same as being allowed to select it. Under
            // a foreign lockdown VPN the platform refuses the bind for this uid
            // ("can't select networks other than N" in netd) and every control-plane
            // call dies with SocketException — sign-in, refresh and import alike.
            // Connecting explicitly keeps the retry safe: nothing has been written
            // yet, so re-issuing on default routing cannot duplicate a POST.
            // Our own tunnel never lands here — the VPN owner may select underlying.
            try {
                connection.connect()
            } catch (e: IOException) {
                Log.w("control_plane_net fallback=default kind=${e.javaClass.simpleName}")
                connection.disconnect()
                connection = open(null)
            }
        }

        try {
            if (body != null) {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(body)
                }
            }

            val code = connection.responseCode
            // Explicit reject: do not read Location or open a second connection.
            if (code in 300..399) {
                throw GetLineAuthException.Protocol(
                    "Unexpected redirect from control-plane API",
                )
            }
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }
            val payload = stream?.readTextAndClose().orEmpty()

            if (code !in 200..299) {
                throw GetLineAuthErrorClassifier.classify(
                    code,
                    errorMessageOf(payload),
                    errorContext,
                )
            }

            if (payload.isBlank()) {
                if (allowEmptyBody) {
                    return@withContext JSONObject()
                }
                throw GetLineAuthException.Protocol("Empty response")
            }

            try {
                JSONObject(payload)
            } catch (_: Exception) {
                if (allowEmptyBody) {
                    JSONObject()
                } else {
                    throw GetLineAuthException.Protocol("Invalid JSON response")
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Best non-VPN internet [Network] for control-plane HTTP.
     * Returns null when none is available or ConnectivityManager is missing
     * (caller falls back to default routing).
     */
    internal fun resolveUnderlyingNetwork(): Network? {
        val cm = connectivityManager ?: connectivityManagerFromGlobal() ?: return null
        return UnderlyingNetworkSelector.pickNetwork(cm)
    }

    private fun connectivityManagerFromGlobal(): ConnectivityManager? {
        val app = runCatching { Global.application }.getOrNull() ?: return null
        return app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    private fun JSONObject.toNativeSession(): NativeSession {
        val access = optStringOrNull("access_token")
            ?: throw GetLineAuthException.Protocol("access_token missing")
        val refresh = optStringOrNull("refresh_token")
            ?: throw GetLineAuthException.Protocol("refresh_token missing")
        val expiresIn = when {
            has("expires_in") -> optLong("expires_in")
            else -> DEFAULT_EXPIRES_IN_SECONDS
        }
        return NativeSession(
            accessToken = access,
            refreshToken = refresh,
            expiresInSeconds = expiresIn,
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)?.toString() ?: return null
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optLongOrNull(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return try {
            getLong(key)
        } catch (_: Exception) {
            optStringOrNull(key)?.toLongOrNull()
        }
    }

    private fun InputStream.readTextAndClose(): String {
        return bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    }

    companion object {
        const val DASHBOARD_PATH = "/api/dashboard"
        const val ACTIVATE_TRIAL_PATH = "/api/dashboard/trial"
        const val EMAIL_SEND_OTP_PATH = "/api/auth/email/send-otp"
        const val EMAIL_VERIFY_OTP_PATH = "/api/auth/email/verify-otp"
        const val NATIVE_EXCHANGE_PATH = "/api/auth/native/exchange"
        /**
         * `register`, not `login`: RWP provisions the trial only on the register
         * branch and treats it as idempotent — an existing account just signs in.
         * `login` silently created accounts that never got a trial.
         */
        private const val EMAIL_AUTH_INTENT = "register"
        private const val TIMEOUT_MS = 30_000
        private const val DEFAULT_EXPIRES_IN_SECONDS = 86_400L

        /**
         * Browser start paths for native PKCE. Query always includes
         * `app_redirect`, `code_challenge`, and explicit `code_challenge_method=S256`.
         * Without `app_redirect` the server may still return 200 and follow the
         * old web path — unit tests lock these parameter names.
         */
        fun startPath(
            method: AuthMethod,
            appRedirect: String,
            codeChallenge: String,
        ): String {
            require(method.requiresBrowser()) {
                "AuthMethod.$method has no browser start path"
            }
            val base = when (method) {
                AuthMethod.Google -> "/api/auth/google/start"
                AuthMethod.Telegram -> "/api/auth/telegram-oidc/start"
                AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
            }
            val enc = StandardCharsets.UTF_8.name()
            return base +
                "?intent=register" +
                "&app_redirect=" + java.net.URLEncoder.encode(appRedirect, enc) +
                "&code_challenge=" + java.net.URLEncoder.encode(codeChallenge, enc) +
                "&code_challenge_method=S256"
        }

        /** JSON body for send-otp (email only; intent is not used by this endpoint). */
        fun emailOtpSendBody(email: String): String {
            return JSONObject().put("email", email).toString()
        }

        /** JSON body for verify-otp; intent is always [EMAIL_AUTH_INTENT]. */
        fun emailOtpVerifyBody(email: String, code: String): String {
            return JSONObject()
                .put("email", email)
                .put("code", code)
                .put("intent", EMAIL_AUTH_INTENT)
                .toString()
        }

        fun parseEmailOtpSendResult(json: JSONObject): EmailOtpSendResult {
            val expiresIn = if (json.has("expires_in") && !json.isNull("expires_in")) {
                try {
                    json.getLong("expires_in")
                } catch (_: Exception) {
                    json.optString("expires_in").toLongOrNull()
                }
            } else {
                null
            }
            return EmailOtpSendResult(expiresInSeconds = expiresIn)
        }

        fun parseEmailOtpVerifyResult(json: JSONObject): EmailOtpVerifyResult {
            val token = json.optString("token").takeIf { it.isNotBlank() && it != "null" }
                ?: throw GetLineAuthException.Protocol("token missing")
            if (!json.has("expires_in") || json.isNull("expires_in")) {
                throw GetLineAuthException.Protocol("expires_in missing")
            }
            val expiresIn = try {
                json.getLong("expires_in")
            } catch (_: Exception) {
                json.optString("expires_in").toLongOrNull()
                    ?: throw GetLineAuthException.Protocol("expires_in missing")
            }
            return EmailOtpVerifyResult(
                webToken = token,
                expiresInSeconds = expiresIn,
            )
        }

        /**
         * Tolerant by design: every field is optional. Dashboard is called after
         * the user confirms trial activation; a partially unreadable payload must
         * not fail the flow when activation flags are still usable.
         */
        fun parseDashboard(json: JSONObject): DashboardInfo {
            val days = if (json.has("trial_days") && !json.isNull("trial_days")) {
                json.optInt("trial_days").takeIf { it > 0 }
            } else {
                null
            }
            return DashboardInfo(
                trialEnabled = json.optBoolean("trial_enabled"),
                trialAvailable = json.optBoolean("trial_available"),
                trialAutoActivated = json.optBoolean("trial_auto_activated"),
                trialDays = days,
                trialPaid = json.optBoolean("trial_paid"),
                trialRecurringOnly = json.optBoolean("trial_recurring_only"),
                freePlanEnabled = json.optBoolean("free_plan_enabled"),
                freePlanAvailable = json.optBoolean("free_plan_available"),
            )
        }

        /**
         * Error bodies come in two shapes: 6.8.0 documents `ErrorResponse
         * {"error": "..."}`, while deployed email auth still answers in plain text.
         * Unwrap the field so the classifier and the user-visible message both see
         * the same string instead of a raw JSON blob.
         *
         * Anything that is not an object with a usable `error` is returned trimmed
         * and unchanged — an unparseable body is still the best message we have.
         */
        fun errorMessageOf(payload: String): String {
            val text = payload.trim()
            if (!text.startsWith("{")) return text
            return try {
                JSONObject(text).optString("error")
                    .takeIf { it.isNotBlank() && it != "null" }
                    ?: text
            } catch (_: Exception) {
                text
            }
        }
    }
}

/**
 * Real control-plane socket. Top-level so it can be the constructor default
 * without the class having to exist first.
 */
private fun openControlPlaneConnection(url: URL, network: Network?): HttpURLConnection {
    return if (network != null) {
        network.openConnection(url) as HttpURLConnection
    } else {
        url.openConnection() as HttpURLConnection
    }
}
