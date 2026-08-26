package pro.getline.vpn.getline.servers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineSubscriptionId

class ServersRefreshFlowTest {
    @Test
    fun noManagedProfile_returnsWithoutRemoteCall() = runBlocking {
        val host = FakeHost(managed = null)

        val outcome = ServersRefreshFlow(host).refresh()

        assertEquals(ServersRefreshFlow.Outcome.NoManagedProfile, outcome)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun managedProfile_updatedResult_isUpdated() = runBlocking {
        val host = FakeHost(managed = "managed", update = ConfigUpdateResult.Updated)

        val outcome = ServersRefreshFlow(host).refresh()

        assertEquals(ServersRefreshFlow.Outcome.Updated, outcome)
        assertEquals(listOf("update:managed"), host.calls)
    }

    @Test
    fun managedProfile_unavailableResult_isFailed() = runBlocking {
        val host = FakeHost(managed = "managed", update = ConfigUpdateResult.Unavailable)

        val outcome = ServersRefreshFlow(host).refresh()

        assertEquals(ServersRefreshFlow.Outcome.Failed, outcome)
        assertEquals(listOf("update:managed"), host.calls)
    }

    @Test
    fun managedProfile_notFoundOrNotRefreshable_preservesTerminalResult() = runBlocking {
        val notFoundHost = FakeHost(managed = "managed", update = ConfigUpdateResult.NotFound)
        val notRefreshableHost = FakeHost(
            managed = "managed",
            update = ConfigUpdateResult.NotRefreshable,
        )

        assertEquals(
            ServersRefreshFlow.Outcome.NotFound,
            ServersRefreshFlow(notFoundHost).refresh(),
        )
        assertEquals(
            ServersRefreshFlow.Outcome.NotRefreshable,
            ServersRefreshFlow(notRefreshableHost).refresh(),
        )
    }

    private class FakeHost(
        private val managed: String?,
        private val update: ConfigUpdateResult = ConfigUpdateResult.Updated,
    ) : ServersRefreshFlow.Host {
        val calls = mutableListOf<String>()

        override fun managedProfileUuid(): String? = managed

        override suspend fun requestConfigUpdate(id: GetLineSubscriptionId): ConfigUpdateResult {
            calls += "update:${id.value}"
            return update
        }
    }
}
