package com.github.kr328.clash.service.model

import com.github.kr328.clash.common.log.Log

/**
 * What an [AccessControlMode], a stored package selection, and optional
 * subscription-managed exclusions mean for `VpnService.Builder`: which
 * packages use the VPN tunnel and which stay on the ordinary system network.
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
         * [AccessControlMode.DenySelected], including when a subscription lists
         * it in `excluded-packages`: the app talks to the control plane through
         * its own tunnel, and letting a profile drop itself out of it would
         * break the app that offers it.
         */
        fun of(
            mode: AccessControlMode,
            packages: Set<String>,
            ownPackage: String,
            subscriptionExcluded: Set<String> = emptySet(),
        ): AccessControlPlan {
            val managed = LinkedHashSet<String>()
            for (pkg in subscriptionExcluded) {
                if (pkg == ownPackage) {
                    Log.w("Access control: subscription cannot exclude $ownPackage")
                    continue
                }
                managed.add(pkg)
            }

            return when (mode) {
                AccessControlMode.AcceptAll ->
                    AccessControlPlan(allowed = emptySet(), disallowed = managed)
                AccessControlMode.AcceptSelected ->
                    AccessControlPlan(
                        allowed = (packages + ownPackage) - managed,
                        disallowed = emptySet(),
                    )
                AccessControlMode.DenySelected ->
                    AccessControlPlan(
                        allowed = emptySet(),
                        disallowed = (packages - ownPackage) + managed,
                    )
            }
        }
    }
}
