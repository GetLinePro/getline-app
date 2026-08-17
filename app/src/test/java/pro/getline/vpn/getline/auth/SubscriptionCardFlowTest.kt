package pro.getline.vpn.getline.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionSummary

class SubscriptionCardFlowTest {
    @Test
    fun noManagedBinding_isEmptyWithoutRemoteCalls() = runBlocking {
        val host = FakeHost(managed = null)

        val outcome = SubscriptionCardFlow(host).materialize(refreshManaged = true)

        assertFalse(outcome.hasManagedBinding)
        assertNull(outcome.summary)
        assertFalse(outcome.failed)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun initialMaterialization_readsSnapshotWithoutRemoteRefresh() = runBlocking {
        val host = FakeHost(managed = "managed", summary = summary("managed"))

        val outcome = SubscriptionCardFlow(host).materialize(refreshManaged = false)

        assertEquals(listOf("find:managed"), host.calls)
        assertEquals("managed", outcome.summary?.uuid)
        assertFalse(outcome.failed)
    }

    @Test
    fun forcedMaterialization_refreshesThenReadsSnapshot() = runBlocking {
        val host = FakeHost(managed = "managed", summary = summary("managed"))

        val outcome = SubscriptionCardFlow(host).materialize(refreshManaged = true)

        assertEquals(listOf("update:managed", "find:managed"), host.calls)
        assertEquals("managed", outcome.summary?.uuid)
        assertFalse(outcome.failed)
    }

    @Test
    fun updateFailure_keepsReturnedSnapshotAndMarksFailure() = runBlocking {
        val local = summary("managed")
        val host = FakeHost(
            managed = "managed",
            update = ConfigUpdateResult.Unavailable,
            summary = local,
        )

        val outcome = SubscriptionCardFlow(host).materialize(refreshManaged = true)

        assertEquals(local, outcome.summary)
        assertTrue(outcome.failed)
    }

    @Test
    fun confirmedMissingImportedRow_isEmpty() = runBlocking {
        val host = FakeHost(managed = "managed", summary = null)

        val outcome = SubscriptionCardFlow(host).materialize(refreshManaged = false)

        assertTrue(outcome.hasManagedBinding)
        assertNull(outcome.summary)
        assertFalse(outcome.failed)
    }

    private class FakeHost(
        private val managed: String?,
        private val update: ConfigUpdateResult = ConfigUpdateResult.Updated,
        private val summary: GetLineSubscriptionSummary? = null,
        private val findUnavailable: Boolean = false,
    ) : SubscriptionCardFlow.Host {
        val calls = mutableListOf<String>()

        override fun managedProfileUuid(): String? = managed

        override suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult {
            calls += "update:${id.value}"
            return update
        }

        override suspend fun findImported(
            id: GetLineSubscriptionId,
        ): GetLineBackendResult<GetLineSubscriptionSummary?> {
            calls += "find:${id.value}"
            return if (findUnavailable) {
                GetLineBackendResult.Unavailable
            } else {
                GetLineBackendResult.Success(summary)
            }
        }
    }

    private fun summary(uuid: String) = GetLineSubscriptionSummary(
        uuid = uuid,
        name = "subscription",
        expire = 0L,
        upload = 0L,
        download = 0L,
        total = 0L,
    )
}
