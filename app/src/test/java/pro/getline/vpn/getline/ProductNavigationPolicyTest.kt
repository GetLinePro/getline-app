package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductNavigationPolicyTest {
    @Test
    fun bestEffortAfterLogout_swallowsCancellation_andContinues() = runBlocking {
        var reached = false
        ProductNavigationPolicy.bestEffortAfterLogout {
            throw CancellationException("deleteManaged cancelled")
        }
        reached = true
        assertTrue(reached)
    }

    @Test
    fun bestEffortAfterLogout_swallowsOrdinaryErrors() = runBlocking {
        var calls = 0
        ProductNavigationPolicy.bestEffortAfterLogout {
            calls++
            error("backend down")
        }
        assertEquals(1, calls)
    }

    @Test
    fun bestEffortAfterLogout_runsBlockOnceOnSuccess() = runBlocking {
        var calls = 0
        ProductNavigationPolicy.bestEffortAfterLogout {
            calls++
        }
        assertEquals(1, calls)
    }
}
