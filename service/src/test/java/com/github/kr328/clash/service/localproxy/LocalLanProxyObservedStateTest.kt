package com.github.kr328.clash.service.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering between the two ways the monitor learns about the world. Callbacks
 * report a change that already happened; a recompute reports how things looked
 * when its query started. Only the first is safe to apply unconditionally.
 */
class LocalLanProxyObservedStateTest {
    @Test
    fun updateReturnsBothSides_andAdvancesRevision() {
        val state = LocalLanProxyObservedState("first")
        val before = state.revision

        val (previous, updated) = state.update { "second" }

        assertEquals("first", previous)
        assertEquals("second", updated)
        assertTrue(state.revision > before)
    }

    @Test
    fun commitOnCurrentRevision_succeeds() {
        val state = LocalLanProxyObservedState("first")

        assertEquals("first" to "second", state.commit(state.revision) { "second" })
    }

    @Test
    fun commitAfterAnInterveningUpdate_isRefused() {
        val state = LocalLanProxyObservedState("first")

        // A recompute reads the revision, then its query is overtaken.
        val revision = state.revision
        state.update { "from-callback" }

        assertNull(state.commit(revision) { "from-stale-query" })
        assertEquals("from-callback", state.current)
    }

    @Test
    fun secondCommitOnTheSameRevision_isRefused() {
        val state = LocalLanProxyObservedState("first")
        val revision = state.revision

        assertNotNull(state.commit(revision) { "second" })
        assertNull(state.commit(revision) { "third" })
    }

    @Test
    fun resetInvalidatesAnInFlightRecompute() {
        // close() must not be undone by a query that started before it.
        val state = LocalLanProxyObservedState("first")
        val revision = state.revision

        state.reset("cleared")

        assertNull(state.commit(revision) { "from-stale-query" })
        assertEquals("cleared", state.current)
    }

    @Test
    fun refusedCommitLeavesCurrentOnTheCallbacksValue() {
        // A read is answered from current, so this is what stops a rejected
        // snapshot from pairing a live address with the network it used to be
        // on — the dead probe route that turns teardown into a fail-stop.
        val state = LocalLanProxyObservedState("from-callback")
        val revision = state.revision - 1

        assertNull(state.commit(revision) { "from-stale-query" })
        assertEquals("from-callback", state.current)
    }

    @Test
    fun currentFollowsEverySuccessfulWrite() {
        val state = LocalLanProxyObservedState("first")

        state.update { "second" }
        assertEquals("second", state.current)

        state.commit(state.revision) { "third" }
        assertEquals("third", state.current)

        state.reset("cleared")
        assertEquals("cleared", state.current)
    }

    @Test
    fun transformSeesThePreviousValue_underTheSameLockThatPublishesIt() {
        // Why commit takes a transform: anything derived from the previous
        // state — the loss counts above all — must be computed where it is
        // published, not read back afterwards.
        val state = LocalLanProxyObservedState("first")

        val result = state.commit(state.revision) { previous -> "$previous+second" }

        assertEquals("first+second", result?.second)
        assertEquals("first+second", state.current)
    }
}
