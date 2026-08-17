package pro.getline.vpn.getline.auth

interface GetLineAuthApi {
    /**
     * Starts browser OAuth for [method] with app-owned PKCE and returns the
     * server-provided `auth_url`.
     *
     * [codeChallenge] is S256 of the verifier held in [PendingNativeAuth].
     * [appRedirect] must be [pro.getline.vpn.AppEnvironment.nativeCallbackUri].
     *
     * [AuthMethod.Email] must not be used here — call [sendEmailOtp] /
     * [verifyEmailOtp] instead.
     */
    suspend fun startBrowserAuth(
        method: AuthMethod,
        codeChallenge: String,
        appRedirect: String,
    ): BrowserAuthStartResponse

    /**
     * Exchanges a one-time native OAuth [code] + PKCE [codeVerifier] for a
     * native session (`POST /api/auth/native/exchange`).
     */
    suspend fun exchangeNativeCode(code: String, codeVerifier: String): NativeSession

    /** Sends a login OTP to [email]. */
    suspend fun sendEmailOtp(email: String): EmailOtpSendResult

    /**
     * Verifies the email OTP and returns a web token for
     * [GetLineSessionRepository.establishFromWebToken].
     * Wire body always includes `"intent":"register"` (not a public parameter).
     */
    suspend fun verifyEmailOtp(email: String, code: String): EmailOtpVerifyResult

    suspend fun getCurrentUser(webToken: String): CurrentUser
    suspend fun generateDeviceKey(webToken: String): DeviceKey
    suspend fun exchangeDeviceKey(deviceKey: String): NativeSession
    suspend fun refresh(refreshToken: String): NativeSession
    suspend fun getSubscriptions(accessToken: String): SubscriptionsResponse

    /**
     * GET /api/dashboard. On current prod this may auto-activate a trial
     * (`trial_auto_activated`). Call only after an explicit user confirmation —
     * never from [GetLineSessionRepository.loadSubscriptionAccountSignal] or unprompted
     * login import.
     */
    suspend fun getDashboard(accessToken: String): DashboardInfo

    /**
     * POST /api/dashboard/trial. OpenAPI 6.8.0 free-trial mutation.
     * Fallback when GET dashboard did not auto-activate and [DashboardInfo.trialAvailable]
     * is still true.
     */
    suspend fun activateTrial(accessToken: String)
}
