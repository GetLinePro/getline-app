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

    data class SignedOut(
        val hasImportedProfile: Boolean,
        val linkOnly: LinkOnlyPresentation? = null,
        val isRefreshing: Boolean = false,
        val refreshFailed: Boolean = false,
    ) : SubscriptionUiState

    data object Failed : SubscriptionUiState
}

/**
 * Presentation for a subscription imported by link (no account session).
 * Built from the local Imported row via [GetLineSubscriptionSummary] — the app
 * knows only what the subscription response itself carried.
 */
data class LinkOnlyPresentation(
    val expireAtEpochMillis: Long?,
    val trafficUsedBytes: Long?,
    val trafficLimitBytes: Long?,
    val trafficUnlimited: Boolean = false,
    val tag: String? = null,
    val status: String? = null,
    val deviceLimit: Int? = null,
) {
    companion object {
        fun fromSummary(summary: GetLineSubscriptionSummary): LinkOnlyPresentation {
            val used = summary.upload + summary.download
            // Counted traffic proves Subscription-Userinfo was parsed, so a
            // non-positive total there is a real "no allowance", not a missing
            // header. Without counters the two are indistinguishable and the
            // card says "unknown" until the first byte is reported.
            val counted = summary.upload > 0L || summary.download > 0L
            return LinkOnlyPresentation(
                expireAtEpochMillis = summary.expire.takeIf { it > 0L },
                // Zero used with no limit cannot be distinguished from "header missing".
                trafficUsedBytes = if (used == 0L && summary.total <= 0L) null else used,
                trafficLimitBytes = summary.total.takeIf { it > 0L },
                trafficUnlimited = counted && summary.total <= 0L,
                tag = summary.tag,
                status = summary.status,
                deviceLimit = summary.deviceLimit?.takeIf { it > 0 },
            )
        }
    }
}

/**
 * Presentation model for the subscription card. Managed profiles use the saved
 * local summary; the account item remains only for the no-binding fallback.
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

        fun fromLocalSummary(
            summary: GetLineSubscriptionSummary,
            string: (Int) -> String,
            nowMillis: Long = System.currentTimeMillis(),
        ): SubscriptionPresentation {
            val link = LinkOnlyPresentation.fromSummary(summary)
            val tag = SubscriptionHeaderDisplay.normalize(link.tag)
            val status = SubscriptionHeaderDisplay.normalize(link.status)
            return SubscriptionPresentation(
                id = summary.uuid,
                title = tag?.let { SubscriptionHeaderDisplay.tariffTitle(it, string) },
                isActive = SubscriptionHeaderDisplay.isActiveStatus(status),
                expireAtEpochMillis = link.expireAtEpochMillis,
                daysLeft = SubscriptionHeaderDisplay.daysLeft(link.expireAtEpochMillis, nowMillis),
                deviceLimit = link.deviceLimit,
                trafficUsedBytes = link.trafficUsedBytes,
                trafficLimitBytes = link.trafficLimitBytes,
                trafficUnlimited = link.trafficUnlimited,
                statusText = status?.let { SubscriptionHeaderDisplay.statusText(it, string) },
                showStatus = status != null,
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

/** Header status is display-only; config synchronization follows the account API. */
internal fun shouldRefreshManagedProfileConfig(preferred: SubscriptionItem?): Boolean =
    preferred?.isActive == true

/** Missing local data is transient only when an active subscription will repair it. */
internal fun shouldTreatManagedSnapshotAsTransient(
    localUnavailable: Boolean,
    localMissing: Boolean,
    repairWillRun: Boolean,
): Boolean =
    localUnavailable || (localMissing && repairWillRun)
