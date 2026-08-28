package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The case that motivates this class is the one that cannot be reproduced by
 * hand: a binder `enable` that entered the coordinator before teardown and
 * returns after it. Its listener is real, and it is also already gone with the
 * Clash runtime — so what it must never do is publish Active behind the
 * session's back.
 */
class LocalLanProxySessionProjectionTest {
    private val published = mutableListOf<LocalLanProxyRuntimeState>()

    private val projection = LocalLanProxySessionProjection { published += it }

    private val active = LocalLanProxyRuntimeState.Active("10.135.213.166", 1234)

    @Test
    fun openStatesTheSessionStartsWithNothingBound() {
        projection.open()

        assertEquals(listOf(LocalLanProxyRuntimeState.Inactive), published)
    }

    @Test
    fun publishesWhileTheSessionIsOpen() {
        projection.open()
        projection.publish(active)

        assertEquals(listOf(LocalLanProxyRuntimeState.Inactive, active), published)
    }

    @Test
    fun inFlightTransactionFinishingAfterCloseCannotPublish() {
        projection.open()
        projection.close()

        // The late transaction's own result, arriving after teardown.
        projection.publish(active)

        assertEquals(
            listOf(LocalLanProxyRuntimeState.Inactive, LocalLanProxyRuntimeState.Inactive),
            published,
        )
    }

    @Test
    fun closeIsTheSessionsLastWordAndIsIdempotent() {
        projection.open()
        projection.publish(active)
        projection.close()
        projection.close()

        assertEquals(
            listOf(LocalLanProxyRuntimeState.Inactive, active, LocalLanProxyRuntimeState.Inactive),
            published,
        )
    }

    /**
     * The interleaving, not the sequence: a publish that has already passed
     * the closed check races [close]. Without holding both under one lock the
     * two land in the wrong order and the session's last word becomes Active —
     * a listener reported as bound after the runtime that owned it is gone.
     */
    @Test
    fun aPublishAlreadyUnderwayCannotOvertakeClose() {
        val insidePublish = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val recorded = mutableListOf<LocalLanProxyRuntimeState>()

        // The sink takes time — it writes the registry and sends a broadcast —
        // so what must be ordered is when each publish *lands*, not when it is
        // called.
        val racing = LocalLanProxySessionProjection { state ->
            if (state is LocalLanProxyRuntimeState.Active) {
                insidePublish.countDown()
                releasePublish.await(5, TimeUnit.SECONDS)
            }

            synchronized(recorded) { recorded += state }
        }

        val publisher = Thread { racing.publish(active) }
        publisher.start()
        assertTrue("publisher never entered the sink", insidePublish.await(5, TimeUnit.SECONDS))

        val closer = Thread { racing.close() }
        closer.start()

        // Let the closer reach close() before releasing the publisher, so this
        // tests the interleaving and not thread-start timing. Correct
        // behaviour parks it on the monitor the publish holds; the bug lets it
        // run to completion and land Inactive first.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (closer.state != Thread.State.BLOCKED &&
            closer.state != Thread.State.TERMINATED &&
            System.nanoTime() < deadline
        ) {
            Thread.sleep(1)
        }

        releasePublish.countDown()
        publisher.join(5_000)
        closer.join(5_000)

        assertEquals(
            listOf(active, LocalLanProxyRuntimeState.Inactive),
            synchronized(recorded) { recorded.toList() },
        )
    }

    @Test
    fun closedSessionAnswersThatNoTransactionMayStart() {
        assertEquals(false, projection.isClosed)

        projection.close()

        assertEquals(true, projection.isClosed)
    }
}
