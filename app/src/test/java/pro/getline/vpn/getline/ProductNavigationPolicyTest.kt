package pro.getline.vpn.getline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun bestEffortAfterLogout_returnsNullWhenBlockFails() = runBlocking {
        val result = ProductNavigationPolicy.bestEffortAfterLogout<String> {
            error("backend down")
        }
        assertNull(result)
    }

    @Test
    fun bestEffortAfterLogout_returnsValueOnSuccess() = runBlocking {
        val result = ProductNavigationPolicy.bestEffortAfterLogout { "deleted" }
        assertEquals("deleted", result)
    }

    /**
     * The orphan today came from clearing the binding after a delete that never
     * happened: the profile stayed and nothing could address it any more.
     */
    @Test
    fun clearBindingAfterSignOut_keepsBindingWhenDeleteFailed() {
        assertFalse(
            ProductNavigationPolicy.clearBindingAfterSignOut(
                hadManagedProfile = true,
                deleteSucceeded = false,
            ),
        )
    }

    @Test
    fun clearBindingAfterSignOut_clearsWhenProfileIsGone() {
        assertTrue(
            ProductNavigationPolicy.clearBindingAfterSignOut(
                hadManagedProfile = true,
                deleteSucceeded = true,
            ),
        )
    }

    @Test
    fun clearBindingAfterSignOut_clearsWhenThereWasNoProfile() {
        assertTrue(
            ProductNavigationPolicy.clearBindingAfterSignOut(
                hadManagedProfile = false,
                deleteSucceeded = false,
            ),
        )
    }

    @Test
    fun canOwnProductShell_requiresSessionOrManagedBinding() {
        assertFalse(ProductNavigationPolicy.canOwnProductShell(false, false))
        assertTrue(ProductNavigationPolicy.canOwnProductShell(true, false))
        assertTrue(ProductNavigationPolicy.canOwnProductShell(false, true))
        assertTrue(ProductNavigationPolicy.canOwnProductShell(true, true))
    }
}
