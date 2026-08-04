package pro.getline.vpn.getline

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProfileCleanupTest {
    @Test
    fun deleted_stopsWhenRequested_andConsumesTombstone() = runBlocking {
        var pending: String? = "old"
        var stops = 0
        val deleted = mutableListOf<String>()

        val result = runPendingManagedProfileCleanup(
            pendingUuid = pending,
            managedUuid = "new",
            canDelete = true,
            stopBeforeDelete = true,
            stopVpn = { stops += 1 },
            deleteManaged = {
                deleted += it.value
                GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
            },
            clearPending = { if (pending == it) pending = null },
        )

        assertEquals(ManagedProfileCleanupResult.Deleted, result)
        assertEquals(1, stops)
        assertEquals(listOf("old"), deleted)
        assertNull(pending)
    }

    @Test
    fun notFound_isCompletedCleanup_andConsumesTombstone() = runBlocking {
        var pending: String? = "already-gone"

        val result = runPendingManagedProfileCleanup(
            pendingUuid = pending,
            managedUuid = "new",
            canDelete = true,
            stopBeforeDelete = false,
            stopVpn = { error("must not stop") },
            deleteManaged = {
                GetLineBackendResult.Success(ManagedProfileDeleteOutcome.NotFound)
            },
            clearPending = { if (pending == it) pending = null },
        )

        assertEquals(ManagedProfileCleanupResult.NotFound, result)
        assertNull(pending)
    }

    @Test
    fun unavailable_keepsTombstone_forNextRepair() = runBlocking {
        var pending: String? = "old"

        val result = runPendingManagedProfileCleanup(
            pendingUuid = pending,
            managedUuid = "new",
            canDelete = true,
            stopBeforeDelete = false,
            stopVpn = { error("must not stop") },
            deleteManaged = { GetLineBackendResult.Unavailable },
            clearPending = { if (pending == it) pending = null },
        )

        assertEquals(ManagedProfileCleanupResult.Unavailable, result)
        assertEquals("old", pending)
    }

    @Test
    fun repeatedRepair_retriesOnlyOldUuid_thenBecomesNoOp() = runBlocking {
        var pending: String? = "old"
        val deleted = mutableListOf<String>()
        var attempts = 0

        suspend fun repair(): ManagedProfileCleanupResult =
            runPendingManagedProfileCleanup(
                pendingUuid = pending,
                managedUuid = "new-active",
                canDelete = true,
                stopBeforeDelete = false,
                stopVpn = { error("must not stop") },
                deleteManaged = {
                    deleted += it.value
                    attempts += 1
                    if (attempts == 1) {
                        GetLineBackendResult.Unavailable
                    } else {
                        GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
                    }
                },
                clearPending = { if (pending == it) pending = null },
            )

        assertEquals(ManagedProfileCleanupResult.Unavailable, repair())
        assertEquals(ManagedProfileCleanupResult.Deleted, repair())
        assertEquals(ManagedProfileCleanupResult.None, repair())
        assertEquals(listOf("old", "old"), deleted)
        assertTrue("the new managed profile must never be deleted", "new-active" !in deleted)
    }

    @Test
    fun tombstoneMatchingCurrentManaged_isDroppedWithoutDelete() = runBlocking {
        var pending: String? = "current"
        var deleteCalled = false

        val result = runPendingManagedProfileCleanup(
            pendingUuid = pending,
            managedUuid = "current",
            canDelete = true,
            stopBeforeDelete = true,
            stopVpn = { error("must not stop") },
            deleteManaged = {
                deleteCalled = true
                GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
            },
            clearPending = { if (pending == it) pending = null },
        )

        assertEquals(ManagedProfileCleanupResult.ProtectedManaged, result)
        assertTrue(!deleteCalled)
        assertNull(pending)
    }

    @Test
    fun failedStop_doesNotSkipDelete() = runBlocking {
        val deleted = mutableListOf<String>()

        val result = runPendingManagedProfileCleanup(
            pendingUuid = "old",
            managedUuid = "new",
            canDelete = true,
            stopBeforeDelete = true,
            stopVpn = { error("service stop failed") },
            deleteManaged = {
                deleted += it.value
                GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
            },
            clearPending = {},
        )

        assertEquals(ManagedProfileCleanupResult.Deleted, result)
        assertEquals(listOf("old"), deleted)
    }

    @Test
    fun replacementNotProven_defersWithoutStopOrDelete() = runBlocking {
        var pending: String? = "only-working-profile"
        var stopCalled = false
        var deleteCalled = false

        val result = runPendingManagedProfileCleanup(
            pendingUuid = pending,
            managedUuid = "missing-replacement",
            canDelete = false,
            stopBeforeDelete = true,
            stopVpn = { stopCalled = true },
            deleteManaged = {
                deleteCalled = true
                GetLineBackendResult.Success(ManagedProfileDeleteOutcome.Deleted)
            },
            clearPending = { if (pending == it) pending = null },
        )

        assertEquals(ManagedProfileCleanupResult.DeferredReplacement, result)
        assertTrue(!stopCalled)
        assertTrue(!deleteCalled)
        assertEquals("only-working-profile", pending)
    }
}
