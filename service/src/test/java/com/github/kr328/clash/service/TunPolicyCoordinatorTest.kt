package com.github.kr328.clash.service

import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.model.AccessControlPlan
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunPolicyCoordinatorTest {
    private val own = "pro.getline.vpn"

    private fun plan(
        mode: AccessControlMode,
        packages: Set<String> = setOf("com.example.bank"),
    ) = AccessControlPlan.of(mode, packages, own)

    @Test
    fun equalPlanIsNoOp() {
        val desired = plan(AccessControlMode.AcceptAll)
        var applies = 0
        val coordinator = TunPolicyCoordinator({ desired }) { applies++ }

        assertEquals(TunPolicyCoordinator.Result.Applied, coordinator.reconcile())
        assertEquals(1, applies)
        assertEquals(TunPolicyCoordinator.Result.Unchanged, coordinator.reconcile())
        assertEquals(1, applies)
        assertEquals(desired, coordinator.appliedPlan)
    }

    @Test
    fun successUpdatesSnapshot() {
        val desired = plan(AccessControlMode.AcceptSelected)
        val coordinator = TunPolicyCoordinator({ desired }) {}

        assertEquals(TunPolicyCoordinator.Result.Applied, coordinator.reconcile())
        assertEquals(desired, coordinator.appliedPlan)
    }

    @Test
    fun failureDoesNotUpdateSnapshot() {
        val desired = plan(AccessControlMode.DenySelected)
        val coordinator = TunPolicyCoordinator({ desired }) { error("attach failed") }

        val result = coordinator.reconcile()

        assertTrue(result is TunPolicyCoordinator.Result.Failed)
        assertEquals("attach failed", (result as TunPolicyCoordinator.Result.Failed).message)
        assertNull(coordinator.appliedPlan)
    }

    @Test
    fun failureLeavesPreviousSnapshot() {
        var store = plan(AccessControlMode.AcceptAll)
        var fail = false
        val coordinator = TunPolicyCoordinator({ store }) {
            if (fail) error("attach failed")
        }

        assertEquals(TunPolicyCoordinator.Result.Applied, coordinator.reconcile())
        val previous = coordinator.appliedPlan

        store = plan(AccessControlMode.AcceptSelected)
        fail = true

        assertTrue(coordinator.reconcile() is TunPolicyCoordinator.Result.Failed)
        assertEquals(previous, coordinator.appliedPlan)
    }

    @Test
    fun burstCoalescesToLatestPlan() = runBlocking {
        val planB = plan(AccessControlMode.AcceptAll)
        val planC = plan(AccessControlMode.AcceptSelected, setOf("com.example.chat"))
        val planD = plan(AccessControlMode.DenySelected, setOf("com.example.maps"))
        var store = planB
        val applied = mutableListOf<AccessControlPlan>()
        val coordinator = TunPolicyCoordinator({ store }) { applied += it }

        store = planB
        coordinator.requestReconcile()
        store = planC
        coordinator.requestReconcile()
        store = planD
        coordinator.requestReconcile()

        withTimeout(1_000) {
            select { coordinator.onRequest { } }
        }
        assertFalse(coordinator.pollRequest())
        assertFalse(coordinator.pollRequest())

        assertEquals(TunPolicyCoordinator.Result.Applied, coordinator.reconcile())
        assertEquals(listOf(planD), applied)
        assertEquals(planD, coordinator.appliedPlan)
    }

    @Test
    fun requestAfterFatalFailureIsNotApplied() {
        var calls = 0
        val coordinator = TunPolicyCoordinator({ plan(AccessControlMode.AcceptAll) }) {
            calls++
            error("fatal")
        }

        assertTrue(coordinator.reconcile() is TunPolicyCoordinator.Result.Failed)
        assertEquals(1, calls)

        coordinator.requestReconcile()
        assertTrue(coordinator.reconcile() is TunPolicyCoordinator.Result.Failed)
        assertEquals(1, calls)
        assertNull(coordinator.appliedPlan)
    }

    @Test
    fun initialReconcileAppliesStoreAfterMissedBroadcast() {
        // Builder would have read A, Activity saved B, broadcast arrived before
        // the receiver was registered. The initial request reads the store, not
        // a captured snapshot, so B is applied.
        val planB = plan(AccessControlMode.AcceptSelected, setOf("com.example.chat"))
        val applied = mutableListOf<AccessControlPlan>()
        val coordinator = TunPolicyCoordinator({ planB }) { applied += it }

        coordinator.requestReconcile()

        assertEquals(TunPolicyCoordinator.Result.Applied, coordinator.reconcile())
        assertEquals(listOf(planB), applied)
    }
}
