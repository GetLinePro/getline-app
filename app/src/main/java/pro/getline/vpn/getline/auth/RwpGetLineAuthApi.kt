package pro.getline.vpn.getline.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class RwpGetLineAuthApi(
    private val origin: String = DEFAULT_ORIGIN,
) : GetLineAuthApi {
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
        val connection = (URL("$origin$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            doInput = true
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
        const val DEFAULT_ORIGIN = "https://app.getline.pro"
        const val EMAIL_SEND_OTP_PATH = "/api/auth/email/send-otp"
        const val EMAIL_VERIFY_OTP_PATH = "/api/auth/email/verify-otp"
        private const val EMAIL_LOGIN_INTENT = "login"
        private const val TIMEOUT_MS = 30_000
        private const val DEFAULT_EXPIRES_IN_SECONDS = 86_400L
        private const val TELEGRAM_RETURN_TO = "https://app.getline.pro/"

        fun startPath(method: AuthMethod): String {
            require(method.requiresBrowser()) {
                "AuthMethod.$method has no browser start path"
            }
            return when (method) {
                AuthMethod.Google -> "/api/auth/google/start"
                AuthMethod.Telegram ->
                    "/api/auth/telegram-oidc/start?intent=login&return_to=" +
                        java.net.URLEncoder.encode(TELEGRAM_RETURN_TO, StandardCharsets.UTF_8.name())
                AuthMethod.Email -> error("unreachable: Email requiresBrowser is false")
            }
        }

        /** JSON body for send-otp (email only; intent is not used by this endpoint). */
        fun emailOtpSendBody(email: String): String {
            return JSONObject().put("email", email).toString()
        }

        /** JSON body for verify-otp; intent is always `"login"`. */
        fun emailOtpVerifyBody(email: String, code: String): String {
            return JSONObject()
                .put("email", email)
                .put("code", code)
                .put("intent", EMAIL_LOGIN_INTENT)
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
    }
}
