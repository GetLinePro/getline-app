package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The product contract of the three split-tunnelling modes (issue #21).
 *
 * "Not in the tunnel" always means the app uses the ordinary system network —
 * these modes are not a firewall, so no mode may produce both an allow list and
 * a deny list, which is the only shape Android could turn into "no network".
 */
class AccessControlPlanTest {
    private val own = "pro.getline.vpn"
    private val selection = setOf("com.example.bank", "com.example.chat")

    @Test
    fun `all apps go through the vpn by default`() {
        val plan = AccessControlPlan.of(AccessControlMode.AcceptAll, selection, own)

        // Neither list is applied, so the builder keeps every uid — including
        // packages the user selected earlier and then switched away from.
        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(emptySet<String>(), plan.disallowed)
    }

    @Test
    fun `only selected puts the selection and this app in the tunnel`() {
        val plan = AccessControlPlan.of(AccessControlMode.AcceptSelected, selection, own)

        assertEquals(selection + own, plan.allowed)
        assertEquals(emptySet<String>(), plan.disallowed)
    }

    @Test
    fun `except selected keeps this app in the tunnel`() {
        val plan = AccessControlPlan.of(
            AccessControlMode.DenySelected,
            selection + own,
            own,
        )

        assertEquals(selection, plan.disallowed)
        assertEquals(emptySet<String>(), plan.allowed)
    }

    @Test
    fun `no mode both allows and disallows`() {
        AccessControlMode.values().forEach { mode ->
            val plan = AccessControlPlan.of(mode, selection, own)

            assertTrue(
                "$mode produced an allow list and a deny list at once",
                plan.allowed.isEmpty() || plan.disallowed.isEmpty(),
            )
        }
    }

    @Test
    fun `an empty selection in only selected still leaves this app connected`() {
        val plan = AccessControlPlan.of(AccessControlMode.AcceptSelected, emptySet(), own)

        assertEquals(setOf(own), plan.allowed)
    }
}
