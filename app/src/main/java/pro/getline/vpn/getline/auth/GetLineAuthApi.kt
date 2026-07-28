package pro.getline.vpn.getline.auth

interface GetLineAuthApi {
    /**
     * Starts browser OAuth for [method] and returns the server-provided `auth_url`.
     *
     * Google is started from the app process (no browser cookies required).
     * Telegram start must run inside the Auth Tab trampoline so PKCE cookies
     * land in the browser jar; prefer [BrowserAuthStarter] for launch URLs.
     *
     * [AuthMethod.Email] must not be used here — call [sendEmailOtp] /
     * [verifyEmailOtp] instead.
     */
    suspend fun startBrowserAuth(method: AuthMethod): BrowserAuthStartResponse

    /** Sends a login OTP to [email]. */
    suspend fun sendEmailOtp(email: String): EmailOtpSendResult

    /**
     * Verifies the email OTP and returns a web token for
     * [GetLineSessionRepository.establishFromWebToken].
     * Wire body always includes `"intent":"login"` (not a public parameter).
     */
    suspend fun verifyEmailOtp(email: String, code: String): EmailOtpVerifyResult

    suspend fun getCurrentUser(webToken: String): CurrentUser
    suspend fun generateDeviceKey(webToken: String): DeviceKey
    suspend fun exchangeDeviceKey(deviceKey: String): NativeSession
    suspend fun refresh(refreshToken: String): NativeSession
    suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse
}
