package pro.getline.vpn.getline.auth

/**
 * One read of the durable session and managed-profile binding state.
 *
 * The nullable fields remain the storage format for compatibility. [provenance]
 * is inferred at this read boundary so callers do not each apply a different
 * interpretation to the same legacy fields.
 */
data class ManagedBindingSnapshot(
    val hasSession: Boolean,
    val managedProfileUuid: String?,
    val managedProfileSource: String?,
    val subscriptionId: String?,
    val provenance: Provenance,
) {
    enum class Provenance {
        AccountBound,
        LinkOnly,
        Absent,
        InconsistentLegacy,
    }

    /** A non-blank UUID is the durable address of the product-owned profile. */
    val hasManagedBinding: Boolean
        get() = managedProfileUuid != null

    val needsPostLoginSubscriptionStep: Boolean
        get() = hasSession && provenance == Provenance.LinkOnly

    /** True when either native account state or a saved URL can repair remotely. */
    val canRemoteRepair: Boolean
        get() = hasSession || managedProfileSource != null

    companion object {
        fun infer(
            hasSession: Boolean,
            managedProfileUuid: String?,
            managedProfileSource: String?,
            subscriptionId: String?,
        ): ManagedBindingSnapshot {
            val uuid = managedProfileUuid.normalized()
            val source = managedProfileSource.normalized()
            val subscription = subscriptionId.normalized()
            val provenance = when {
                uuid == null && source == null && subscription == null ->
                    Provenance.Absent
                uuid != null && subscription != null ->
                    Provenance.AccountBound
                uuid != null && source != null && subscription == null ->
                    Provenance.LinkOnly
                else ->
                    Provenance.InconsistentLegacy
            }
            return ManagedBindingSnapshot(
                hasSession = hasSession,
                managedProfileUuid = uuid,
                managedProfileSource = source,
                subscriptionId = subscription,
                provenance = provenance,
            )
        }

        private fun String?.normalized(): String? = this?.takeIf { it.isNotBlank() }
    }
}
