package pro.getline.vpn.getline.auth

/** Account controls are derived separately from subscription-card state. */
object SubscriptionAccountCapabilityPolicy {
    fun shouldResumeManagedSubscriptionSignIn(
        hasSession: Boolean,
        hasManagedBinding: Boolean,
        needsPostLoginStep: Boolean,
    ): Boolean =
        hasManagedBinding && (!hasSession || needsPostLoginStep)

    fun canSafelySignOut(
        hasSession: Boolean,
        needsPostLoginStep: Boolean,
    ): Boolean = hasSession && !needsPostLoginStep
}
