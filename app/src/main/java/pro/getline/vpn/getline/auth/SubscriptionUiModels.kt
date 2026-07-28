package pro.getline.vpn.getline.auth

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

    data class SignedOut(
        val hasImportedProfile: Boolean,
    ) : SubscriptionUiState

    data object Failed : SubscriptionUiState
}

/**
 * Presentation model for the subscription card.
 * Built from [SubscriptionItem] via [selectPreferred] — never raw network DTOs in View.
 */
data class SubscriptionPresentation(
    val id: String?,
    val title: String,
    val isActive: Boolean,
    val expireAtEpochMillis: Long?,
    val daysLeft: Int?,
    /** total_device_limit ?: device_limit; null or non-positive → hide devices row. */
    val deviceLimit: Int?,
    val trafficUsedBytes: Long?,
    val trafficLimitBytes: Long?,
    val trafficUnlimited: Boolean,
) {
    companion object {
        /**
         * Maps a preferred [SubscriptionItem] to presentation fields.
         * [devicesCount] is intentionally ignored (including sentinel -1).
         */
        fun fromPreferred(item: SubscriptionItem, fallbackTitle: String): SubscriptionPresentation {
            val title = item.displayName?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val deviceLimit = item.effectiveDeviceLimit?.takeIf { it > 0 }
            val traffic = item.traffic
            return SubscriptionPresentation(
                id = item.id,
                title = title,
                isActive = item.isActive,
                expireAtEpochMillis = item.expireAtEpochMillis?.takeIf { it > 0L },
                daysLeft = item.daysLeft?.takeIf { it >= 0 },
                deviceLimit = deviceLimit,
                trafficUsedBytes = traffic?.usedBytes,
                trafficLimitBytes = traffic?.limitBytes,
                trafficUnlimited = traffic?.isUnlimited == true,
            )
        }
    }
}

/** Outcome of an authenticated /api/subscriptions load after selectPreferred(). */
sealed interface SubscriptionLoadResult {
    data class Success(val preferred: SubscriptionItem?) : SubscriptionLoadResult
    data object SignedOut : SubscriptionLoadResult
    data object TransientFailure : SubscriptionLoadResult
}
