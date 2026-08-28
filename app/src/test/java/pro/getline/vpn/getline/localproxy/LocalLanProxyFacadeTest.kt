package pro.getline.vpn.getline.localproxy

import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeState
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The facade's contract is that the snapshot is *coherent*: enabled state
 * always comes from the session, configuration always from the store, and no
 * call result is ever cached in place of either. These tests drive it with a
 * scripted runtime so the disagreements that matter — a call that succeeded
 * against a session that no longer holds the listener — are reachable.
 */
class LocalLanProxyFacadeTest {
    /**
     * Stands in for the encrypted store. Same two operations, same
     * owner-scoped behaviour that matters to the facade — a record belongs to
     * one owner, and an unusable store answers null rather than a default.
     */
    private class FakeSettings(var owner: String? = "owner-a", val usable: Boolean = true) : LocalLanProxySettings {
        private val records = mutableMapOf<String, LocalLanProxyUserConfig>()

        var generated = 0

        override fun loadOrCreate(): LocalLanProxyUserConfig? {
            if (!usable) return null

            return records.getOrPut(owner ?: "none") {
                generated++
                LocalLanProxyUserConfig(20000 + generated, "getline", "generated-secret-$generated")
            }
        }

        override fun save(config: LocalLanProxyUserConfig): Boolean {
            if (!usable) return false

            records[owner ?: "none"] = config

            return true
        }
    }

    private class FakeRuntime : LocalLanProxyRuntimeClient {
        var runtimeState: LocalLanProxyRuntimeState = LocalLanProxyRuntimeState.Inactive
        var enableResult = LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Enabled)
        var disableResult = LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled)

        var enableCalls = 0
        var lastConfig: LocalLanProxyUserConfig? = null

        /** Completed by the test to hold a transaction open mid-flight. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeResult {
            enableCalls++
            lastConfig = config
            gate?.await()

            if (enableResult.status == LocalLanProxyRuntimeResult.Status.Enabled) {
                runtimeState = LocalLanProxyRuntimeState.Active("10.135.213.166", config.port)
            }

            return enableResult
        }

        override suspend fun disable(): LocalLanProxyRuntimeResult {
            gate?.await()

            if (disableResult.status == LocalLanProxyRuntimeResult.Status.Disabled) {
                runtimeState = LocalLanProxyRuntimeState.Inactive
            }

            return disableResult
        }

        override suspend fun state(): LocalLanProxyRuntimeState = runtimeState
    }

    private val runtime = FakeRuntime()

    private var vpnRunning = true

    private val settings = FakeSettings()

    private fun facade(store: LocalLanProxySettings = settings) = LocalLanProxyFacade(
        store = store,
        runtime = runtime,
        vpnRunning = { vpnRunning },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Before
    fun setUp() {
        vpnRunning = true
        settings.owner = "owner-a"
    }

    @Test
    fun beforeTheFirstReadNothingIsClaimed() {
        val snapshot = facade().state.value

        assertEquals(LocalLanProxyStatus.Loading, snapshot.status)
        assertEquals(LocalLanProxyAvailability.Unknown, snapshot.availability)
        assertNull(snapshot.config)
    }

    @Test
    fun refreshLoadsSettingsAndReportsTheSessionAsTheSourceOfEnabledState() = runBlocking {
        runtime.runtimeState = LocalLanProxyRuntimeState.Active("10.135.213.166", 4321)

        val facade = facade()
        facade.refresh()

        val snapshot = facade.state.value
        assertEquals(LocalLanProxyStatus.Active("10.135.213.166", 4321), snapshot.status)
        assertEquals(LocalLanProxyAvailability.Ready, snapshot.availability)
        assertEquals("getline", snapshot.config?.username)
    }

    @Test
    fun withoutAVpnSettingsAreStillEditableButEnableIsNotOffered() = runBlocking {
        vpnRunning = false

        val facade = facade()
        facade.refresh()

        assertEquals(LocalLanProxyAvailability.VpnOffline, facade.state.value.availability)
        assertEquals(LocalLanProxyStatus.Disabled, facade.state.value.status)
    }

    @Test
    fun enableSendsTheStoredConfigAndAdoptsTheSessionState() = runBlocking {
        val facade = facade()
        facade.refresh()
        val stored = facade.state.value.config!!

        assertEquals(LocalLanProxyResult.Success, facade.enable())

        assertEquals(stored, runtime.lastConfig)
        assertEquals(
            LocalLanProxyStatus.Active("10.135.213.166", stored.port),
            facade.state.value.status,
        )
    }

    @Test
    fun runtimeOutcomesBecomeProductResults() = runBlocking {
        val cases = listOf(
            LocalLanProxyRuntimeResult.Status.VpnUnavailable to LocalLanProxyResult.VpnUnavailable,
            LocalLanProxyRuntimeResult.Status.NoEligibleEndpoint to LocalLanProxyResult.NoEligibleLan,
            LocalLanProxyRuntimeResult.Status.PortOccupied to LocalLanProxyResult.PortOccupied,
            LocalLanProxyRuntimeResult.Status.ApplyFailed to LocalLanProxyResult.ApplyFailed,
            LocalLanProxyRuntimeResult.Status.SafetyStop to LocalLanProxyResult.SafetyStop,
        )

        val facade = facade()

        for ((status, expected) in cases) {
            runtime.enableResult = LocalLanProxyRuntimeResult(status)

            assertEquals(expected, facade.enable())
            // A failed enable never leaves the screen claiming an endpoint.
            assertEquals(LocalLanProxyStatus.Disabled, facade.state.value.status)
        }
    }

    @Test
    fun aFailedEnableDoesNotLeakItsMessageIntoTheProductResult() = runBlocking {
        runtime.enableResult = LocalLanProxyRuntimeResult(
            LocalLanProxyRuntimeResult.Status.ApplyFailed,
            message = "listen tcp 10.0.0.1:4321: bind: address already in use",
        )

        assertEquals(LocalLanProxyResult.ApplyFailed, facade().enable())
    }

    @Test
    fun disableReturnsToDisabled() = runBlocking {
        val facade = facade()

        facade.enable()
        assertTrue(facade.state.value.status is LocalLanProxyStatus.Active)

        assertEquals(LocalLanProxyResult.Success, facade.disable())
        assertEquals(LocalLanProxyStatus.Disabled, facade.state.value.status)
    }

    @Test
    fun aTransactionInFlightShowsAsTransitional() = runBlocking {
        val facade = facade()
        val gate = CompletableDeferred<Unit>()
        runtime.gate = gate

        val enabling = async { facade.enable() }

        // The scripted runtime is parked inside enable(); the snapshot must
        // already say so rather than still claim disabled.
        while (facade.state.value.status != LocalLanProxyStatus.Enabling) {
            kotlinx.coroutines.yield()
        }

        gate.complete(Unit)
        assertEquals(LocalLanProxyResult.Success, enabling.await())
        assertTrue(facade.state.value.status is LocalLanProxyStatus.Active)
    }

    @Test
    fun updateConfigValidatesBeforePersisting() = runBlocking {
        val facade = facade()
        facade.refresh()
        val before = facade.state.value.config

        val result = facade.updateConfig(LocalLanProxyUserConfig(80, "getline", "secret"))

        assertEquals(LocalLanProxyResult.InvalidSettings(LocalLanProxyResult.InvalidSettings.Field.Port), result)
        assertEquals(before, facade.state.value.config)
    }

    @Test
    fun updateConfigPersistsAndRepublishes() = runBlocking {
        val facade = facade()
        facade.refresh()

        val edited = LocalLanProxyUserConfig(4321, "someone", "another-secret")
        assertEquals(LocalLanProxyResult.Success, facade.updateConfig(edited))

        assertEquals(edited, facade.state.value.config)
        assertEquals(edited, settings.loadOrCreate())
    }

    @Test
    fun settingsCannotBeEditedWhileTheListenerIsBound() = runBlocking {
        val facade = facade()
        facade.enable()
        val active = facade.state.value.config!!

        val result = facade.updateConfig(LocalLanProxyUserConfig(4321, "someone", "another-secret"))

        assertEquals(LocalLanProxyResult.ActiveNotEditable, result)
        assertEquals(active, settings.loadOrCreate())
    }

    @Test
    fun unusableStorageBlocksEnableInsteadOfSendingNothing() = runBlocking {
        val facade = facade(FakeSettings(usable = false))

        facade.refresh()

        assertEquals(LocalLanProxyAvailability.SettingsUnavailable, facade.state.value.availability)
        assertEquals(LocalLanProxyResult.SettingsUnavailable, facade.enable())
        assertEquals(0, runtime.enableCalls)
    }

    @Test
    fun aNewOwnerDoesNotInheritTheStoredCredentials() = runBlocking {
        val facade = facade()
        facade.refresh()
        val mine = facade.state.value.config!!

        settings.owner = "owner-b"
        facade.refresh()

        assertNotEquals(mine.password, facade.state.value.config!!.password)
    }
}
