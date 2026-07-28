package pro.getline.vpn.getline.accountportal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPortalVisitCoordinatorTest {
    @Test
    fun successfulLaunch_withoutOnStop_doesNotRefreshOnStart() {
        val c = AccountPortalVisitCoordinator()
        c.onLaunched()
        assertFalse(c.onHostStarted())
        assertTrue(c.isVisitPending)
    }

    @Test
    fun launch_stop_start_refreshesExactlyOnce() {
        val c = AccountPortalVisitCoordinator()
        c.onLaunched()
        c.onHostStopped()
        assertTrue(c.onHostStarted())
        assertFalse(c.isVisitPending)
        assertFalse(c.onHostStarted())
    }

    @Test
    fun ordinaryOnStart_withoutLaunch_noRefresh() {
        val c = AccountPortalVisitCoordinator()
        assertFalse(c.onHostStarted())
        c.onHostStopped()
        assertFalse(c.onHostStarted())
    }

    @Test
    fun subsequentOnStart_doesNotSecondRefresh() {
        val c = AccountPortalVisitCoordinator()
        c.onLaunched()
        c.onHostStopped()
        assertTrue(c.onHostStarted())
        c.onHostStopped()
        assertFalse(c.onHostStarted())
    }

    @Test
    fun rotationBeforeOpen_noPortalRefresh() {
        // Rotation: stop/start without a successful portal launch.
        val c = AccountPortalVisitCoordinator()
        c.onHostStopped()
        assertFalse(c.onHostStarted())
        assertTrue(c.canLaunch())
    }

    @Test
    fun failedLaunch_doesNotSetPendingReturn() {
        val c = AccountPortalVisitCoordinator()
        c.onLaunchFailed()
        assertFalse(c.isVisitPending)
        assertTrue(c.canLaunch())
        c.onHostStopped()
        assertFalse(c.onHostStarted())
    }

    @Test
    fun logout_clearsPendingReturn() {
        val c = AccountPortalVisitCoordinator()
        c.onLaunched()
        c.onHostStopped()
        c.clear()
        assertFalse(c.isVisitPending)
        assertFalse(c.onHostStarted())
        assertTrue(c.canLaunch())
    }

    @Test
    fun canLaunch_falseWhileVisitPending() {
        val c = AccountPortalVisitCoordinator()
        assertTrue(c.canLaunch())
        c.onLaunched()
        assertFalse(c.canLaunch())
        c.onHostStopped()
        assertFalse(c.canLaunch())
        assertTrue(c.onHostStarted())
        assertTrue(c.canLaunch())
    }
}
