package pro.getline.vpn.getline.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.github.kr328.clash.common.Global
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.getline.vpn.AppEnvironment
import pro.getline.vpn.GetLineControlPlaneHostPolicy
import java.io.BufferedReader
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
) : GetLineAuthApi {
    init {
        // Fail closed before any network I/O if origin is wrong for this flavor.
        GetLineControlPlaneHostPolicy.requireApiOrigin(origin)
    }

    override suspend fun startBrowserAuth(method: AuthMethod): BrowserAuthStartResponse {
        require(method.requiresBrowser()) {
            "AuthMethod.$method cannot start browser auth"
        }
        val path = startPath(method)
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
        // Prefer the underlying (non-VPN) network. When TunService is up with a
        // broken/expired outbound, default routing goes through fake-ip + proxy
        // and control-plane GETs fail even though the account/subscription is fine.
        val connection = openControlPlaneConnection(url).apply {
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
                throw GetLineAuthErrorClassifier.classify(code, payload, errorContext)
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

    private fun openControlPlaneConnection(url: URL): HttpURLConnection {
        val network = resolveUnderlyingNetwork()
        return if (network != null) {
            network.openConnection(url) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
    }

    /**
     * Best non-VPN internet [Network] for control-plane HTTP.
     * Returns null when none is available or ConnectivityManager is missing
     * (caller falls back to default routing).
     */
    internal fun resolveUnderlyingNetwork(): Network? {
        val cm = connectivityManager ?: connectivityManagerFromGlobal() ?: return null
        return pickUnderlyingNetwork(cm)
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
        const val EMAIL_SEND_OTP_PATH = "/api/auth/email/send-otp"
        const val EMAIL_VERIFY_OTP_PATH = "/api/auth/email/verify-otp"
        /**
         * `register`, not `login`: RWP provisions the trial only on the register
         * branch and treats it as idempotent — an existing account just signs in.
         * `login` silently created accounts that never got a trial.
         */
        private const val EMAIL_AUTH_INTENT = "register"
        private const val TIMEOUT_MS = 30_000
        private const val DEFAULT_EXPIRES_IN_SECONDS = 86_400L

        /**
         * Whether [NetworkCapabilities] are safe for control-plane HTTP
         * (must not re-enter the app VPN tunnel).
         *
         * Requires [NetworkCapabilities.NET_CAPABILITY_VALIDATED]: INTERNET alone
         * means the network is *configured* for internet, not that it currently
         * works (captive portal / unvalidated Wi‑Fi can rank ahead of working cell).
         */
        internal fun isUsableUnderlying(caps: NetworkCapabilities?): Boolean {
            if (caps == null) return false
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return false
            }
            // Configured-for-internet is not enough; skip captive / unvalidated.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return false
            }
            // VPN transport: app traffic would re-enter the tunnel.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return false
            }
            // Prefer NOT_VPN when the platform sets it (API 21+ on underlying).
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                return false
            }
            return true
        }

        /**
         * Pure selection: prefer [active] when usable, else first usable in [all].
         */
        internal fun <T> pickUnderlying(
            active: T?,
            activeUsable: Boolean,
            all: List<T>,
            isUsable: (T) -> Boolean,
        ): T? {
            if (active != null && activeUsable) return active
            return all.firstOrNull(isUsable)
        }

        /**
         * Pick a validated non-VPN network with INTERNET.
         * Prefer the active network when it itself is not a VPN; otherwise scan.
         */
        internal fun pickUnderlyingNetwork(cm: ConnectivityManager): Network? {
            fun usable(network: Network): Boolean =
                isUsableUnderlying(cm.getNetworkCapabilities(network))

            val active = cm.activeNetwork
            @Suppress("DEPRECATION")
            val all = cm.allNetworks.toList()
            return pickUnderlying(
                active = active,
                activeUsable = active != null && usable(active),
                all = all,
                isUsable = ::usable,
            )
        }

        /**
         * Browser start paths, kept in sync with the deployed trampoline HTML
         * under `docs/spikes/android-auth/` — this is the revert path if
         * trampolines misbehave, so a stale `intent` here would bring the
         * "registered without a trial" bug back.
         */
        fun startPath(method: AuthMethod): String {
            require(method.requiresBrowser()) {
                "AuthMethod.$method has no browser start path"
            }
            return when (method) {
                AuthMethod.Google -> "/api/auth/google/start?intent=register"
                AuthMethod.Telegram ->
                    "/api/auth/telegram-oidc/start?intent=register&return_to=" +
                        java.net.URLEncoder.encode(
                            AppEnvironment.telegramReturnTo,
                            StandardCharsets.UTF_8.name(),
                        )
                AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
            }
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
         * Tolerant by design: every field is optional. The call is made for its
         * server-side effect (trial provisioning), so a payload we cannot fully
         * read must not fail the login it is part of.
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
            )
        }
    }
}
