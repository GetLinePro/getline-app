package pro.getline.vpn.getline.auth

import pro.getline.vpn.getline.GetLineSubscriptionSummary

/**
 * Read-only Subscription destination UI state.
 * Owned by the Home shell Activity (not per-tab View instances).
 */
sealed interface SubscriptionUiState {
    data object Loading : SubscriptionUiState

    data class Ready(
        val subscription: SubscriptionPresentation,
        val isRefreshing: Boolean = false,
        val transientError: Boolean = false,
    ) : SubscriptionUiState

    data object Empty : SubscriptionUiState

    data object Failed : SubscriptionUiState
}

/**
 * Presentation model for the subscription card. The saved local summary is the
 * only source, independent of account-session availability.
 */
data class SubscriptionPresentation(
    val id: String?,
    /** Tariff label. Null when the profile supplied no valid tag. */
    val title: String?,
    val isActive: Boolean,
    val expireAtEpochMillis: Long?,
    val daysLeft: Int?,
    /** total_device_limit ?: device_limit; null or non-positive → hide devices row. */
    val deviceLimit: Int?,
    val trafficUsedBytes: Long?,
    val trafficLimitBytes: Long?,
    val trafficUnlimited: Boolean,
    /**
     * When set, the pill shows this text. Null keeps the API Active/Inactive
     * strings. [showStatus] false hides the pill (no status header).
     */
    val statusText: String? = null,
    val showStatus: Boolean = true,
) {
    companion object {
        /**
         * The only card source, with or without a session:
         * whatever the subscription response itself carried, as saved on the row.
         */
        fun fromLocalSummary(
            summary: GetLineSubscriptionSummary,
            string: (Int) -> String,
            nowMillis: Long = System.currentTimeMillis(),
        ): SubscriptionPresentation {
            val expireAtEpochMillis = summary.expire.takeIf { it > 0L }
            val used = summary.upload + summary.download
            // Counted traffic proves Subscription-Userinfo was parsed, so a
            // non-positive total there is a real "no allowance", not a missing
            // header. Without counters the two are indistinguishable and the
            // card says "unknown" until the first byte is reported.
            val counted = summary.upload > 0L || summary.download > 0L
            val tag = SubscriptionHeaderDisplay.normalize(summary.tag)
            val status = SubscriptionHeaderDisplay.normalize(summary.status)
            return SubscriptionPresentation(
                id = summary.uuid,
                title = tag?.let { SubscriptionHeaderDisplay.tariffTitle(it, string) },
                isActive = SubscriptionHeaderDisplay.isActiveStatus(status),
                expireAtEpochMillis = expireAtEpochMillis,
                daysLeft = SubscriptionHeaderDisplay.daysLeft(expireAtEpochMillis, nowMillis),
                deviceLimit = summary.deviceLimit?.takeIf { it > 0 },
                // Zero used with no limit cannot be distinguished from "header missing".
                trafficUsedBytes = if (used == 0L && summary.total <= 0L) null else used,
                trafficLimitBytes = summary.total.takeIf { it > 0L },
                trafficUnlimited = counted && summary.total <= 0L,
                statusText = status?.let { SubscriptionHeaderDisplay.statusText(it, string) },
                showStatus = status != null,
            )
        }
    }
}

/** Best-effort account hint. It never carries card presentation data. */
enum class SubscriptionAccountSignal {
    RefreshManagedProfile,
    NoRefresh,
    Unavailable,
}

/** The only account-signal decision allowed to enter the local card refresh path. */
internal fun SubscriptionAccountSignal.shouldRefreshManagedProfile(
    hasManagedBinding: Boolean,
): Boolean = hasManagedBinding && this == SubscriptionAccountSignal.RefreshManagedProfile
