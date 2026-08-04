package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ImportedProfileActivationTest {
    @Test
    fun success_returnsWithoutRetry() = runBlocking {
        var attempts = 0

        val result = runActivation {
            attempts += 1
            GetLineBackendResult.Success(true)
        }

        assertEquals(GetLineBackendResult.Success(true), result)
        assertEquals(1, attempts)
    }

    @Test
    fun notImported_isTerminalAndDoesNotRetry() = runBlocking {
        var attempts = 0

        val result = runActivation {
            attempts += 1
            GetLineBackendResult.Success(false)
        }

        assertEquals(GetLineBackendResult.Success(false), result)
        assertEquals(1, attempts)
    }

    @Test
    fun foregroundUnavailable_isTerminalWithoutRetry() = runBlocking {
        var attempts = 0

        val result = runActivation {
            attempts += 1
            GetLineBackendResult.Unavailable
        }

        assertEquals(GetLineBackendResult.Unavailable, result)
        assertEquals(1, attempts)
    }

    @Test
    fun unavailableAfterStop_waitsForForegroundAndTriesAgain() = runBlocking {
        var foreground = true
        var attempts = 0
        var foregroundWaits = 0

        val result = activateImportedProfileWhenForeground(
            isForeground = { foreground },
            awaitForeground = {
                foregroundWaits += 1
                foreground = true
            },
            activate = {
                attempts += 1
                if (attempts == 1) {
                    foreground = false
                    GetLineBackendResult.Unavailable
                } else {
                    GetLineBackendResult.Success(true)
                }
            },
        )

        assertEquals(GetLineBackendResult.Success(true), result)
        assertEquals(2, attempts)
        assertEquals(2, foregroundWaits)
    }

    @Test
    fun repeatedStops_allowAnotherAttemptUntilOneCompletesInForeground() = runBlocking {
        var foreground = true
        var attempts = 0
        var foregroundWaits = 0

        val result = activateImportedProfileWhenForeground(
            isForeground = { foreground },
            awaitForeground = {
                foregroundWaits += 1
                foreground = true
            },
            activate = {
                attempts += 1
                if (attempts < 3) foreground = false
                GetLineBackendResult.Unavailable
            },
        )

        assertEquals(GetLineBackendResult.Unavailable, result)
        assertEquals(3, attempts)
        assertEquals(3, foregroundWaits)
    }

    @Test
    fun cancellationWhileWaitingForForeground_propagatesWithoutActivation() = runBlocking {
        var attempts = 0

        try {
            activateImportedProfileWhenForeground(
                isForeground = { false },
                awaitForeground = { throw CancellationException("screen destroyed") },
                activate = {
                    attempts += 1
                    GetLineBackendResult.Success(true)
                },
            )
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the durable managed binding is recovered by Home.
        }

        assertEquals(0, attempts)
    }

    private suspend fun runActivation(
        activate: suspend () -> GetLineBackendResult<Boolean>,
    ): GetLineBackendResult<Boolean> = activateImportedProfileWhenForeground(
        isForeground = { true },
        awaitForeground = {},
        activate = activate,
    )
}
