package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The product contract of the three split-tunnelling modes (issue #21).
 *
 * "Not in the tunnel" always means the app uses the ordinary system network —
 * these modes are not a firewall, so no mode may produce both an allow list and
 * a deny list, which is the only shape Android could turn into "no network".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
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

    @Test
    fun `all apps disallows subscription exclusions`() {
        val excluded = setOf("com.example.maps", "com.example.mail")
        val plan = AccessControlPlan.of(AccessControlMode.AcceptAll, selection, own, excluded)

        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(excluded, plan.disallowed)
    }

    @Test
    fun `all except selected unions user and subscription exclusions`() {
        val excluded = setOf("com.example.maps")
        val plan = AccessControlPlan.of(AccessControlMode.DenySelected, selection, own, excluded)

        assertEquals(emptySet<String>(), plan.allowed)
        assertEquals(selection + excluded, plan.disallowed)
    }

    @Test
    fun `only selected subtracts subscription exclusions from the allow list`() {
        val excluded = setOf("com.example.bank")
        val plan = AccessControlPlan.of(AccessControlMode.AcceptSelected, selection, own, excluded)

        assertEquals(setOf("com.example.chat", own), plan.allowed)
        assertEquals(emptySet<String>(), plan.disallowed)
    }

    @Test
    fun `subscription cannot exclude the app own package`() {
        val excluded = setOf(own, "com.example.maps")
        val all = AccessControlPlan.of(AccessControlMode.AcceptAll, selection, own, excluded)
        val deny = AccessControlPlan.of(AccessControlMode.DenySelected, selection, own, excluded)
        val allow = AccessControlPlan.of(AccessControlMode.AcceptSelected, selection, own, excluded)

        assertEquals(setOf("com.example.maps"), all.disallowed)
        assertEquals(selection + "com.example.maps", deny.disallowed)
        assertTrue(own in allow.allowed)
        assertTrue(own !in all.disallowed)
        assertTrue(own !in deny.disallowed)
    }

    @Test
    fun `changing unused subscription exclusions keeps the same effective plan`() {
        val first = AccessControlPlan.of(
            AccessControlMode.AcceptSelected,
            setOf("com.example.chrome"),
            own,
            setOf("com.example.maps"),
        )
        val second = AccessControlPlan.of(
            AccessControlMode.AcceptSelected,
            setOf("com.example.chrome"),
            own,
            setOf("com.example.mail"),
        )

        assertEquals(first, second)
        assertEquals(setOf("com.example.chrome", own), first.allowed)
    }

    @Test
    fun `subscription union still never allows and disallows together`() {
        val excluded = setOf("com.example.maps")
        AccessControlMode.values().forEach { mode ->
            val plan = AccessControlPlan.of(mode, selection, own, excluded)

            assertTrue(
                "$mode produced an allow list and a deny list at once",
                plan.allowed.isEmpty() || plan.disallowed.isEmpty(),
            )
        }
    }
}
