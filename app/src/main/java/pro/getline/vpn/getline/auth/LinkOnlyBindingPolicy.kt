package pro.getline.vpn.getline.auth

/**
 * True when the managed profile was imported by subscription URL without an
 * account-bound [rememberedSubscriptionId]. Matches
 * [SessionSubscriptionConsistency] BindingWithoutSession after login handoff
 * still has tokens but no subscription id yet written on the post-login path.
 */
object LinkOnlyBindingPolicy {
    fun isLinkOnlyBinding(
        managedUuid: String?,
        managedSource: String?,
        rememberedSubscriptionId: String?,
    ): Boolean =
        ManagedBindingSnapshot.infer(
            hasSession = false,
            managedProfileUuid = managedUuid,
            managedProfileSource = managedSource,
            subscriptionId = rememberedSubscriptionId,
        ).provenance == ManagedBindingSnapshot.Provenance.LinkOnly

    /**
     * Session exists but the managed binding is still link-only: the post-login
     * subscription step never finished (mismatch dialog, process death, failed
     * import). Decision 1 forbids landing Home in this mixed state — resume
     * [importPreferredSubscription] until the user chooses or import succeeds.
     */
    fun needsPostLoginSubscriptionStep(
        hasSession: Boolean,
        managedUuid: String?,
        managedSource: String?,
        rememberedSubscriptionId: String?,
    ): Boolean =
        ManagedBindingSnapshot.infer(
            hasSession = hasSession,
            managedProfileUuid = managedUuid,
            managedProfileSource = managedSource,
            subscriptionId = rememberedSubscriptionId,
        ).needsPostLoginSubscriptionStep
}
