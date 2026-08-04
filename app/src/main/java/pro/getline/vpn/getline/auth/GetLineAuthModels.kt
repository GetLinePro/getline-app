package pro.getline.vpn.getline.auth

data class CurrentUser(
    val customerId: String?,
    val username: String?,
    val firstName: String?,
    val telegramId: String?,
    val role: String?,
)

data class DeviceKey(
    val value: String,
)

data class NativeSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/**
 * Trial / free-plan decision slice of GET /api/dashboard.
 *
 * On prod (probed 2026-07-31 and 2026-08-04) a first GET for a fresh account can
 * still auto-create a trial and set [trialAutoActivated]. OpenAPI 6.8.0 documents
 * activation as POST /api/dashboard/trial instead. Treat this GET as a possible
 * mutation: never call it before the user confirms trial activation.
 *
 * [trialDays] and eligibility flags are only known from a dashboard response; a
 * mutating first response is already post-activate, so pre-tap UI must not show
 * a day count or claim trial availability.
 *
 * [trialAutoActivated] means "activated during this request", not "can be activated".
 */
data class DashboardInfo(
    val trialEnabled: Boolean,
    val trialAvailable: Boolean,
    val trialAutoActivated: Boolean,
    val trialDays: Int?,
    val trialPaid: Boolean = false,
    val trialRecurringOnly: Boolean = false,
    val freePlanEnabled: Boolean = false,
    val freePlanAvailable: Boolean = false,
)

data class SubscriptionTraffic(
    val usedBytes: Long,
    val limitBytes: Long,
    val usedPercent: Double,
    val isUnlimited: Boolean,
)

/**
 * Preferred subscription from GET /api/subscriptions.
 *
 * [id] is stringified (API may send a number). Used for profile reuse matching.
 * [expireAtEpochMillis] is the Instant equivalent; no java.time dependency (minSdk 23).
 */
data class SubscriptionItem(
    val id: String?,
    val name: String?,
    val planName: String?,
    val planType: String?,
    val kind: String?,
    val isPrimary: Boolean,
    val isActive: Boolean,
    val expireAtEpochMillis: Long?,
    val daysLeft: Int?,
    val deviceLimit: Int?,
    val totalDeviceLimit: Int?,
    val devicesCount: Int?,
    val traffic: SubscriptionTraffic?,
    val autopayEnabled: Boolean,
    val renewalDisabled: Boolean,
    val planArchived: Boolean,
    val subscriptionLink: String?,
) {
    /** Display plan label: plan_name, else name. */
    val displayName: String?
        get() = planName?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() }

    /** Device cap for UI: total_device_limit ?: device_limit. */
    val effectiveDeviceLimit: Int?
        get() = totalDeviceLimit ?: deviceLimit
}

data class SubscriptionsResponse(
    val autopayAvailable: Boolean,
    val subscriptions: List<SubscriptionItem>,
) {
    /**
     * First primary subscription with a usable link, else the first usable one.
     *
     * [isUsable] filters candidates *during* selection rather than after it.
     * The import path passes the environment allowlist: otherwise a first
     * primary whose link is malformed or points at another environment wins the
     * selection, fails the check that follows, and takes a perfectly good second
     * subscription down with it — the whole login ends in ImportFailed while the
     * account has a working subscription.
     *
     * UI callers keep the default. A subscription the app cannot import is still
     * the user's subscription and has to stay on screen.
     */
    fun selectPreferred(
        isUsable: (SubscriptionItem) -> Boolean = { true },
    ): SubscriptionItem? {
        val candidates = subscriptions.filter {
            !it.subscriptionLink.isNullOrBlank() && isUsable(it)
        }
        return candidates.firstOrNull { it.isPrimary } ?: candidates.firstOrNull()
    }
}

/** Result of POST /api/auth/email/send-otp. HTTP 2xx is success; TTL is optional. */
data class EmailOtpSendResult(
    val expiresInSeconds: Long? = null,
)

/**
 * Result of POST /api/auth/email/verify-otp.
 * [webToken] is the JSON `token` field (same handoff as browser auth_token).
 * Server may also return `user.*` / other fields — not promoted to domain.
 */
data class EmailOtpVerifyResult(
    val webToken: String,
    val expiresInSeconds: Long,
)

sealed class GetLineAuthException(message: String) : Exception(message) {
    class InvalidCallback(message: String = "Invalid authentication callback") :
        GetLineAuthException(message)

    class HttpFailure(val code: Int, message: String) :
        GetLineAuthException(message)

    class Protocol(message: String) : GetLineAuthException(message)

    class Cancelled : GetLineAuthException("Authentication cancelled")

    class VerificationFailed : GetLineAuthException("Redirect verification failed")

    class RateLimited(message: String = "Rate limited") : GetLineAuthException(message)

    class NoAccount(message: String = "No account") : GetLineAuthException(message)

    class EmailDomainNotAllowed(message: String = "Email domain not allowed") :
        GetLineAuthException(message)

    class InvalidOtp(message: String = "Invalid OTP") : GetLineAuthException(message)

    class OtpExpired(message: String = "OTP expired") : GetLineAuthException(message)

    /**
     * Authenticated GET /api/subscriptions succeeded with nothing importable
     * (empty list or items without a usable link). Not a network/HTTP failure.
     */
    class NoSubscription : GetLineAuthException("No subscription with import URL")
}

/**
 * Error classification context for [GetLineAuthErrorClassifier].
 *
 * [EmailOtpVerify] maps residual **4xx** failures to
 * [GetLineAuthException.InvalidOtp] (web UI treats unknown verify errors as a bad
 * code). Other endpoints must not treat generic bodies like "Invalid email
 * address" as OTP errors. A 5xx is never the user's code — see [classify].
 */
enum class AuthErrorContext {
    Default,
    EmailOtpVerify,
    /** Preserve HTTP status and response text for refresh-specific classification. */
    NativeRefresh,
}

/**
 * Maps HTTP status + response body text to [GetLineAuthException] subtypes.
 *
 * RWP email errors are plain-text bodies (e.g. `email_domain_not_allowed`,
 * `no_account`); browser endpoints typically only need [GetLineAuthException.HttpFailure].
 */
object GetLineAuthErrorClassifier {
    fun classify(
        statusCode: Int,
        body: String,
        context: AuthErrorContext = AuthErrorContext.Default,
    ): GetLineAuthException {
        val text = body.trim()
        val lower = text.lowercase()
        val message = text.ifBlank { "HTTP $statusCode" }

        if (context == AuthErrorContext.NativeRefresh) {
            return GetLineAuthException.HttpFailure(statusCode, message)
        }
        // A broken server is not a user error, whatever its body says. Gateways and
        // error pages are free to contain words like "expired"; classifying on that
        // would tell the user to fix a code that is still valid.
        if (statusCode >= 500) {
            return GetLineAuthException.HttpFailure(statusCode, message)
        }
        if (statusCode == 429) {
            return GetLineAuthException.RateLimited(message)
        }
        when {
            lower.contains("no_account") ->
                return GetLineAuthException.NoAccount(message)
            lower.contains("email_domain_not_allowed") ->
                return GetLineAuthException.EmailDomainNotAllowed(message)
            lower.contains("expired") ->
                return GetLineAuthException.OtpExpired(message)
            lower.contains("too many") ->
                return GetLineAuthException.RateLimited(message)
        }
        // InvalidOtp only for verify residuals the client could have caused; 5xx
        // already returned above, so the outage never reaches this line.
        if (context == AuthErrorContext.EmailOtpVerify) {
            return GetLineAuthException.InvalidOtp(message)
        }
        return GetLineAuthException.HttpFailure(statusCode, message)
    }
}
