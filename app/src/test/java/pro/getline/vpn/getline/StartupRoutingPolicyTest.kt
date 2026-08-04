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
 * This contract has been rewritten several times — pending import ahead of the
 * managed profile, a dead backend routed Home instead of Onboarding — and each
 * time the evidence was a user who landed on the wrong screen. The priority order
 * is the whole point: getting it wrong offers a fresh import to someone who
 * already has a working VPN profile, or drops a half-finished one.
 */
class StartupRoutingPolicyTest {

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

    @Test
    fun pendingImport_resumesOnboarding_withoutAskingTheBackend() = runBlocking {
        val probe = Probe(snapshot = SessionRoutingSnapshot(storeOk = true, hasPendingImport = true))

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("pending_import", route.reason)
        assertEquals(0, probe.backendCalls)
        assertEquals("na", route.imported)
        assertEquals("na", route.backend)
    }

    /**
     * A cold start in the middle of an import must resume it, not land Home on the
     * strength of the profile the import is about to replace — the pending payload
     * carries the orphan-cleanup UUID and there is no second chance to read it.
     */
    @Test
    fun pendingImport_winsOverAnExistingManagedProfile() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(
                storeOk = true,
                hasManagedProfile = true,
                hasPendingImport = true,
            ),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Onboarding, route.target)
        assertEquals("pending_import", route.reason)
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
     * A dead `:background` process is not an empty account. Onboarding would offer
     * a fresh import over a profile that is still there.
     */
    @Test
    fun deadBackend_goesHomeRecoverable_notOnboarding() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
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
    fun importedProfile_goesHome() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = true),
            imported = GetLineBackendResult.Success(true),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("has_import", route.reason)
        assertEquals("1", route.imported)
        assertEquals("ok", route.backend)
        assertFalse(route.backendUnavailable)
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
     * must not be mistaken for a proven-empty session: the backend still gets
     * asked, and the breadcrumb still carries `storeOk = false` so the reading of
     * the line is not ambiguous.
     */
    @Test
    fun failedStoreRead_stillAsksTheBackend_andKeepsTheUncertaintyVisible() = runBlocking {
        val probe = Probe(
            snapshot = SessionRoutingSnapshot(storeOk = false),
            imported = GetLineBackendResult.Success(true),
        )

        val route = StartupRoutingPolicy.decide(false, probe::snapshot, probe::imported)

        assertEquals(LaunchTarget.Home, route.target)
        assertEquals("has_import", route.reason)
        assertEquals(1, probe.backendCalls)
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
