package pro.getline.vpn.getline.accountportal

sealed interface AccountPortalLaunchResult {
    data object Launched : AccountPortalLaunchResult
    data object NoBrowserAvailable : AccountPortalLaunchResult
    data object RejectedUri : AccountPortalLaunchResult
    /** Launch was ignored because a portal visit is already in progress. */
    data object AlreadyInProgress : AccountPortalLaunchResult
    data class Failed(val cause: Throwable) : AccountPortalLaunchResult
}
