package com.github.kr328.clash.service.clash.module

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class ConfigurationReloadMailboxTest {
    @Test
    fun directRequests_areCorrelatedAndSerialized() = runBlocking {
        val mailbox = ConfigurationReloadMailbox(timeoutMs = 2_000)
        val firstId = UUID.randomUUID()

        val first = async(start = CoroutineStart.UNDISPATCHED) { mailbox.reloadAndAwait() }
        val firstRequest = nextRequest(mailbox)
        val second = async(start = CoroutineStart.UNDISPATCHED) { mailbox.reloadAndAwait() }

        // The second caller cannot enter the rendezvous channel while the
        // first caller still owns its correlated completion.
        val prematureSecond = withTimeoutOrNull(50) { nextRequest(mailbox) }
        assertNull(prematureSecond)
        assertFalse(second.isCompleted)

        firstRequest.complete(ConfigurationReloadResult.Loaded(firstId))
        assertEquals(ConfigurationReloadResult.Loaded(firstId), first.await())

        val secondRequest = nextRequest(mailbox)
        secondRequest.complete(ConfigurationReloadResult.Failed("second failed"))
        assertEquals(ConfigurationReloadResult.Failed("second failed"), second.await())
        mailbox.close()
    }

    @Test
    fun requestTimesOutWhileQueueOrNativeLoadDoesNotProgress() = runBlocking {
        val mailbox = ConfigurationReloadMailbox(timeoutMs = 25)

        try {
            mailbox.reloadAndAwait()
            fail("expected bounded reload timeout")
        } catch (e: ConfigurationReloadTimeoutException) {
            assertTrue(e.message.orEmpty().contains("25ms"))
        } finally {
            mailbox.close()
        }
    }

    @Test
    fun shutdownFailsAcceptedAndFutureRequests() = runBlocking {
        val mailbox = ConfigurationReloadMailbox(timeoutMs = 2_000)
        val accepted = async(start = CoroutineStart.UNDISPATCHED) { mailbox.reloadAndAwait() }
        nextRequest(mailbox)

        mailbox.close()

        try {
            accepted.await()
            fail("accepted request must fail on shutdown")
        } catch (_: CancellationException) {
            // Expected.
        }

        try {
            mailbox.reloadAndAwait()
            fail("future request must fail on shutdown")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    private suspend fun nextRequest(
        mailbox: ConfigurationReloadMailbox,
    ): ConfigurationReloadMailbox.Request = withTimeout(1_000) {
        select { mailbox.onReceive { it } }
    }
}
