package pro.getline.vpn.getline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a cold start lands, as a table.
 *
 * This contract has been rewritten several times — empty+dead-backend →
 * Onboarding (#98) vs session+dead → Home — and each time the evidence was a
 * user who landed on the wrong screen. The priority order is the whole point:
 * getting it wrong offers a fresh import to someone who already has a working
 * VPN profile, or traps a clean install on Home. Process death does not resume
 * an in-flight fetch; managed/session state starts a new attempt.
 */
class StartupRoutingPolicyTest {

    @Test
    fun isAdvancedLaunch_requiresDebugBuild() {
        assertTrue(isAdvancedLaunch(openAdvancedExtra = true, isDebugBuild = true))
        assertFalse(isAdvancedLaunch(openAdvancedExtra = true, isDebugBuild = false))
        assertFalse(isAdvancedLaunch(openAdvancedExtra = false, isDebugBuild = true))
        assertFalse(isAdvancedLaunch(openAdvancedExtra = false, isDebugBuild = false))
    }

    @Test
    fun openAdvanced_skipsStoreAndBackendEntirely() = runBlocking {
        val probe = Probe()

        val route = StartupRoutingPolicy.decide(
            openAdvanced = true,
            readSnapshot = probe::snapshot,
            hasImported = probe::imported,
        )

        assertEquals(LaunchTarget.Advanced, route.target)
        assertEquals("open_advanced", route.reason)
        // Nothing was read, so nothing may be claimed about the session.
        assertNull(route.snapshot)
        assertEquals(0, probe.snapshotReads)
        assertEquals(0, probe.backendCalls)
    }

    /**
     * GL-22 / #76: release builds must not honour EXTRA_OPEN_ADVANCED.
     * MainActivity gates via [isAdvancedLaunch]; when that returns false the
     * extra is treated as a normal product cold start.
     */
    @Test
    fun openAdvanced_ignoredInRelease_routesAsNormalProductStart() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Success(false),
        )

        val openAdvanced = isAdvancedLaunch(
            openAdvancedExtra = true,
            isDebugBuild = false,
        )
        val route = StartupRoutingPolicy.decide(
            openAdvanced = openAdvanced,
            readSnapshot = probe::snapshot,
            hasImported = probe::imported,
        )

        assertFalse(openAdvanced)
        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("no_import", route.reason)
        assertEquals(1, probe.snapshotReads)
        assertEquals(1, probe.backendCalls)
    }

    @Test
    fun managedProfile_goesHome_withoutAskingTheBackend() = runBlocking {
        val probe = Probe(snapshot = SessionRoutingSnapshot(storeOk = true, hasManagedProfile = true))

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("managed_profile", route.reason)
        assertEquals(0, probe.backendCalls)
        assertFalse(route.backendUnavailable)
    }

    /**
     * Managed binding wins even when the post-login step never finished. Forcing
     * Onboarding there traps a working profile behind Retry/Diagnostics forever
     * when the failure is permanent (no importable subscription, offline).
     */
    @Test
    fun managedProfile_goesHome_evenWithAnIncompletePostLoginState() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(
                storeOk = true,
                hasSession = true,
                hasManagedProfile = true,
            ),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("managed_profile", route.reason)
    }

    /**
     * Proven-empty local state + dead `:background` is clean install / wipe, not a
     * hidden profile. Home with "service unavailable" is a dead end (#98).
     *
     * Product decision: same branch covers inventory-only profiles with no session
     * and no managed UUID — Onboarding (re-import clobber risk) beats trapping
     * every clean install on Home. No local signal can prove inventory without IPC.
     */
    @Test
    fun deadBackend_emptyLocal_goesOnboarding() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Unavailable,
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("backend_unavailable_empty", route.reason)
        assertTrue(route.backendUnavailable)
        assertEquals("unavailable", route.backend)
        assertEquals("na", route.imported)
    }

    /**
     * Session without managed still may own inventory only visible via IPC.
     * Onboarding would offer a fresh import over that profile.
     */
    @Test
    fun deadBackend_withSession_goesHomeRecoverable() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true, hasSession = true),
            imported = GetLineBackendResult.Unavailable,
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("backend_unavailable", route.reason)
        assertTrue("Home must be told the backend is down", route.backendUnavailable)
        assertEquals("unavailable", route.backend)
        assertEquals("na", route.imported)
    }

    @Test
    fun importedProfile_withSession_goesHome() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true, hasSession = true),
            imported = GetLineBackendResult.Success(true),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("has_import", route.reason)
        assertEquals("1", route.imported)
        assertEquals("ok", route.backend)
        assertFalse(route.backendUnavailable)
    }

    /**
     * Inventory without a session or managed binding is an orphan, not a product
     * subscription. Home would paint Empty+Retry with no account action (#150).
     * Still asks the backend so this stays distinct from a dead `:background`.
     */
    @Test
    fun orphanImport_goesOnboarding() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Success(true),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("orphan_import", route.reason)
        assertEquals("1", route.imported)
        assertEquals("ok", route.backend)
        assertEquals(1, probe.backendCalls)
        assertFalse(route.backendUnavailable)
    }

    /**
     * Session is not a blanket ticket to Home. Backend-proven empty inventory
     * still goes to Onboarding; session-only Home is the Unavailable recovery
     * branch, not Success(false). This is a new attempt from persisted session
     * state, not resume of a killed fetch.
     */
    @Test
    fun sessionWithoutManaged_emptyInventory_goesOnboarding() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true, hasSession = true),
            imported = GetLineBackendResult.Success(false),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("no_import", route.reason)
        assertEquals("0", route.imported)
        assertEquals("ok", route.backend)
    }

    @Test
    fun nothingImported_goesOnboarding() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Success(false),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("no_import", route.reason)
        assertEquals("0", route.imported)
        assertEquals("ok", route.backend)
    }

    /**
     * A failed store read leaves every flag at its default `false`. Those defaults
     * must not be mistaken for a proven-empty session or sent into code that will
     * reopen the same broken store without a recovery UI.
     */
    @Test
    fun failedStoreRead_opensRecoverableStorageError_withoutAskingBackend() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = false),
            imported = GetLineBackendResult.Success(true),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("session_storage_unavailable", route.reason)
        assertEquals(0, probe.backendCalls)
        assertFalse(route.snapshot!!.storeOk)
    }

    @Test
    fun snapshotIsReadOnce_perDecision() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Success(false),
        )

        StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        // MasterKey init is not free; the snapshot exists to be read exactly once.
        assertEquals(1, probe.snapshotReads)
    }

    /** Counts the two questions the policy is allowed to ask. */
    private class Probe(
        private val snapshot: SessionRoutingSnapshot = SessionRoutingSnapshot(storeOk = true),
        private val imported: GetLineBackendResult<Boolean> = GetLineBackendResult.Success(false),
    ) {
        var snapshotReads = 0
            private set
        var backendCalls = 0
            private set

        suspend fun snapshot(): SessionRoutingSnapshot {
            snapshotReads++
            return snapshot
        }

        suspend fun imported(): GetLineBackendResult<Boolean> {
            backendCalls++
            return imported
        }
    }
}
