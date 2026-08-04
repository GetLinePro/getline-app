package pro.getline.vpn.getlineui.model

import androidx.annotation.StringRes
import pro.getline.vpn.getlineui.R

enum class GetLineProductState(
    @StringRes val title: Int,
    @StringRes val explanation: Int,
    val loading: Boolean = false,
) {
    Content(0, 0),
    Loading(
        R.string.get_line_state_loading_title,
        R.string.get_line_state_loading_explanation,
        loading = true,
    ),
    /** Explicit VPN config repair in progress (local then optional remote). */
    PreparingVpn(
        R.string.get_line_state_preparing_vpn_title,
        R.string.get_line_state_preparing_vpn_explanation,
        loading = true,
    ),
    Offline(
        R.string.get_line_state_offline_title,
        R.string.get_line_state_offline_explanation,
    ),
    BackendUnavailable(
        R.string.get_line_state_backend_unavailable_title,
        R.string.get_line_state_backend_unavailable_explanation,
    ),
    NoProfile(
        R.string.get_line_state_no_profile_title,
        R.string.get_line_state_no_profile_explanation,
    ),
    /**
     * Local/automatic VPN prepare failed. Product UI: Retry — no profile picker.
     */
    ConnectionRepairFailed(
        R.string.get_line_state_connection_repair_failed_title,
        R.string.get_line_state_connection_repair_failed_explanation,
    ),
    /**
     * Remote re-provision needed or attempted and failed (often offline / API).
     */
    ConnectionRestoreFailed(
        R.string.get_line_state_connection_restore_failed_title,
        R.string.get_line_state_connection_restore_failed_explanation,
    ),
    ImportFailed(
        R.string.get_line_state_import_failed_title,
        R.string.get_line_state_import_failed_explanation,
    ),
    AuthFailed(
        R.string.get_line_state_auth_failed_title,
        R.string.get_line_state_auth_failed_explanation,
    ),
    /** Damaged encrypted session was removed; providers remain available. */
    SessionStorageRecovered(
        R.string.get_line_state_session_storage_recovered_title,
        R.string.get_line_state_session_storage_recovered_explanation,
    ),
    /** Encrypted storage still cannot be opened after one safe reset attempt. */
    SessionStorageUnavailable(
        R.string.get_line_state_session_storage_unavailable_title,
        R.string.get_line_state_session_storage_unavailable_explanation,
    ),
    /** Email step: enter address to request a login code. */
    AuthEmailEntry(
        R.string.get_line_state_auth_email_entry_title,
        R.string.get_line_state_auth_email_entry_explanation,
    ),
    /** OTP step after a successful send — what to do with the code. */
    AuthEmailOtpSent(
        R.string.get_line_state_auth_email_otp_sent_title,
        R.string.get_line_state_auth_email_otp_sent_explanation,
    ),
    /** Email OTP: wrong code — stay on OTP step. */
    AuthInvalidOtp(
        R.string.get_line_state_auth_invalid_otp_title,
        R.string.get_line_state_auth_invalid_otp_explanation,
    ),
    /** Email OTP: code past TTL — stay on OTP step; user may resend. */
    AuthOtpExpired(
        R.string.get_line_state_auth_otp_expired_title,
        R.string.get_line_state_auth_otp_expired_explanation,
    ),
    /** Email: domain not on allowlist — stay on email step. */
    AuthEmailDomainNotAllowed(
        R.string.get_line_state_auth_email_domain_title,
        R.string.get_line_state_auth_email_domain_explanation,
    ),
    /** Login email has no account — message only, no register CTA. */
    AuthNoAccount(
        R.string.get_line_state_auth_no_account_title,
        R.string.get_line_state_auth_no_account_explanation,
    ),
    /** HTTP 429 / send cooldown — wait before resend. */
    AuthRateLimited(
        R.string.get_line_state_auth_rate_limited_title,
        R.string.get_line_state_auth_rate_limited_explanation,
    ),
    SubscriptionExpired(
        R.string.get_line_state_expired_title,
        R.string.get_line_state_expired_explanation,
    ),
}

enum class GetLineRecoveryAction(@StringRes val label: Int) {
    None(0),
    Retry(R.string.get_line_action_retry),
    ImportSubscription(R.string.get_line_add_existing_subscription),
    OpenProfiles(R.string.get_line_action_choose_profile),
    OpenAccount(R.string.get_line_action_open_account),
    /** Opens existing GetLine onboarding/auth flow (Subscription signed-out). */
    SignIn(R.string.get_line_action_sign_in),
}
