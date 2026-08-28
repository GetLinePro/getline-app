package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Test

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

    @Test
    fun closedSessionAnswersThatNoTransactionMayStart() {
        assertEquals(false, projection.isClosed)

        projection.close()

        assertEquals(true, projection.isClosed)
    }
}
