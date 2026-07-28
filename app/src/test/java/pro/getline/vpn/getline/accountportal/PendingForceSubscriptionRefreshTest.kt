package pro.getline.vpn.getline.accountportal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingForceSubscriptionRefreshTest {
    @Test
    fun forceRejectedWhileInFlight_queuesOneFollowUp() {
        val q = PendingForceSubscriptionRefresh()
        // beginRefresh returned false → mark
        q.mark()
        assertTrue(q.isPending)
        // first in-flight request completes → consume once
        assertTrue(q.consume())
        assertFalse(q.isPending)
        // no second follow-up
        assertFalse(q.consume())
    }

    @Test
    fun multipleMarks_stillOneConsume() {
        val q = PendingForceSubscriptionRefresh()
        q.mark()
        q.mark()
        assertTrue(q.consume())
        assertFalse(q.consume())
    }

    @Test
    fun clear_dropsPendingWithoutRefresh() {
        val q = PendingForceSubscriptionRefresh()
        q.mark()
        q.clear()
        assertFalse(q.consume())
    }

    @Test
    fun noMark_consumeIsFalse() {
        val q = PendingForceSubscriptionRefresh()
        assertFalse(q.consume())
    }
}
