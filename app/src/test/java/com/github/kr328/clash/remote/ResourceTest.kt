package com.github.kr328.clash.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * Binder handoff for RemoteService. [Resource.reject] fails only in-flight
 * waiters — no sticky throw (Advanced/withClash must not crash). Profile
 * fail-fast is [com.github.kr328.clash.util.withProfile] + [Service.wasBindRejected].
 */
class ResourceTest {

    @Test
    fun get_returnsValueAfterSet() = runBlocking {
        val resource = Resource<String>()
        resource.set("ok")
        assertEquals("ok", resource.get())
    }

    @Test
    fun peek_isNullUntilSet() {
        val resource = Resource<String>()
        assertNull(resource.peek())
        resource.set("ok")
        assertEquals("ok", resource.peek())
    }

    /**
     * In-flight waiters are failed so profile [withTimeout] paths wake early.
     * [com.github.kr328.clash.util.withClash] must catch and re-park — reject is
     * not "Advanced should crash".
     */
    @Test
    fun reject_failsPendingWaiterImmediately() = runBlocking {
        val resource = Resource<String>()
        val waiter = async {
            try {
                resource.get()
                fail("expected reject")
                null
            } catch (e: IllegalStateException) {
                e.message
            }
        }
        delay(20)
        resource.reject(IllegalStateException("bind_rejected"))
        assertEquals("bind_rejected", withTimeout(500) { waiter.await() })
    }

    @Test
    fun reject_doesNotStickyFailLaterGet() = runBlocking {
        val resource = Resource<String>()
        resource.reject(IllegalStateException("bind_rejected"))
        // No sticky: a later set + get still works (and get alone would wait).
        resource.set("bound")
        assertEquals("bound", resource.get())
    }

    @Test
    fun reject_whileValueHeld_isNoOp() = runBlocking {
        val resource = Resource<String>()
        resource.set("live")
        resource.reject(IllegalStateException("bind_rejected"))
        assertEquals("live", resource.get())
    }
}
