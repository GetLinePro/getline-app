package com.github.kr328.clash.service.model

/**
 * What an [AccessControlMode] and a stored package selection mean for
 * `VpnService.Builder`: which packages are put inside the tunnel and which are
 * kept out of it.
 *
 * Split from the builder call on purpose. The three modes are a product promise
 * (issue #21) that cannot be asserted against a real `VpnService.Builder` in a
 * unit test, and the two selective modes fail in opposite directions when the
 * mapping is wrong: an app that should have gone direct silently stays in the
 * tunnel, or the other way round.
 *
 * Only one of the two sets is ever non-empty — Android does not accept an allow
 * list and a deny list on the same builder.
 */
data class AccessControlPlan(
    val allowed: Set<String>,
    val disallowed: Set<String>,
) {
    companion object {
        /**
         * [ownPackage] is this app itself. It is forced into the tunnel under
         * [AccessControlMode.AcceptSelected] and never excluded under
         * [AccessControlMode.DenySelected]: the app talks to the control plane
         * through its own tunnel, and letting the user drop itself out of it
         * would be a setting that breaks the app that offers it.
         */
        fun of(
            mode: AccessControlMode,
            packages: Set<String>,
            ownPackage: String,
        ): AccessControlPlan = when (mode) {
            AccessControlMode.AcceptAll ->
                AccessControlPlan(allowed = emptySet(), disallowed = emptySet())
            AccessControlMode.AcceptSelected ->
                AccessControlPlan(allowed = packages + ownPackage, disallowed = emptySet())
            AccessControlMode.DenySelected ->
                AccessControlPlan(allowed = emptySet(), disallowed = packages - ownPackage)
        }
    }
}
