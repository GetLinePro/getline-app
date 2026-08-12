package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLoginPipelineTest {
    @Test
    fun directPath_claimsExactlyOnce() {
        val pipeline = PostLoginPipeline()

        assertTrue(pipeline.tryStart())
        assertEquals(PostLoginPipeline.State.Running, pipeline.state)
        assertFalse(pipeline.tryStart())

        pipeline.finish()

        assertEquals(PostLoginPipeline.State.Terminal, pipeline.state)
        assertFalse(pipeline.tryStart())
    }

    @Test
    fun siblingHandoff_defersThenClaimsExactlyOnce() {
        val pipeline = PostLoginPipeline()

        pipeline.defer()
        pipeline.defer()

        assertTrue(pipeline.isDeferred)
        assertTrue(pipeline.tryStart())
        assertFalse(pipeline.tryStart())

        pipeline.finish()

        assertEquals(PostLoginPipeline.State.Terminal, pipeline.state)
    }

    @Test
    fun handoffDuringRunningOrTerminal_doesNotScheduleSecondRun() {
        val pipeline = PostLoginPipeline()

        assertTrue(pipeline.tryStart())
        pipeline.defer()
        assertFalse(pipeline.isDeferred)

        pipeline.finish()
        pipeline.defer()

        assertEquals(PostLoginPipeline.State.Terminal, pipeline.state)
        assertFalse(pipeline.tryStart())
    }

    @Test
    fun clearedDeferredRequest_allowsLateHandoffToClaim() {
        val pipeline = PostLoginPipeline()

        pipeline.defer()
        pipeline.clearDeferred()

        assertEquals(PostLoginPipeline.State.NotStarted, pipeline.state)
        assertTrue(pipeline.tryStart())
    }

    @Test
    fun reset_allowsNextBrowserAttempt() {
        val pipeline = PostLoginPipeline()
        assertTrue(pipeline.tryStart())
        pipeline.finish()

        pipeline.reset()

        assertEquals(PostLoginPipeline.State.NotStarted, pipeline.state)
        assertTrue(pipeline.tryStart())
    }

    @Test(expected = IllegalStateException::class)
    fun resetWhileRunning_failsClosed() {
        val pipeline = PostLoginPipeline()
        assertTrue(pipeline.tryStart())

        pipeline.reset()
    }
}
