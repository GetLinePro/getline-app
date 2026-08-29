package pro.getline.vpn.getline.localproxy

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

        /** Set to make a discard fail the way an unwritable store would. */
        var discardable = true

        var discardCalls = 0

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

        override fun belongsToAnotherOwner(): Boolean =
            usable && records.keys.any { it != (owner ?: "none") }

        override fun discardForeignRecord(): Boolean {
            discardCalls++

            if (!discardable) return false

            records.keys.retainAll(setOf(owner ?: "none"))

            return true
        }

        fun recordOwners(): Set<String> = records.keys.toSet()
    }

    private class FakeRuntime : LocalLanProxyRuntimeClient {
        var runtimeState: LocalLanProxyRuntimeState = LocalLanProxyRuntimeState.Inactive
        var enableResult: LocalLanProxyRuntimeOutcome =
            LocalLanProxyRuntimeOutcome.Answered(LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Enabled))
        var disableResult: LocalLanProxyRuntimeOutcome =
            LocalLanProxyRuntimeOutcome.Answered(LocalLanProxyRuntimeResult(LocalLanProxyRuntimeResult.Status.Disabled))

        var enableCalls = 0
        var lastConfig: LocalLanProxyUserConfig? = null

        /** Completed by the test to hold a transaction open mid-flight. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun enable(config: LocalLanProxyUserConfig): LocalLanProxyRuntimeOutcome {
            enableCalls++
            lastConfig = config
            gate?.await()

            if (enableResult.statusOrNull() == LocalLanProxyRuntimeResult.Status.Enabled) {
                runtimeState = LocalLanProxyRuntimeState.Active("10.135.213.166", config.port)
            }

            return enableResult
        }

        override suspend fun disable(): LocalLanProxyRuntimeOutcome {
            gate?.await()

            if (disableResult.statusOrNull() == LocalLanProxyRuntimeResult.Status.Disabled) {
                runtimeState = LocalLanProxyRuntimeState.Inactive
            }

            return disableResult
        }

        private fun LocalLanProxyRuntimeOutcome.statusOrNull() =
            (this as? LocalLanProxyRuntimeOutcome.Answered)?.result?.status

        override suspend fun state(): LocalLanProxyRuntimeState = runtimeState
    }

    private val runtime = FakeRuntime()

    private var vpnRunning = true

    private val settings = FakeSettings()

    private fun answered(status: LocalLanProxyRuntimeResult.Status): LocalLanProxyRuntimeOutcome =
        LocalLanProxyRuntimeOutcome.Answered(LocalLanProxyRuntimeResult(status))

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
            runtime.enableResult = answered(status)

            assertEquals(expected, facade.enable())
            // A failed enable never leaves the screen claiming an endpoint.
            assertEquals(LocalLanProxyStatus.Disabled, facade.state.value.status)
        }
    }

    @Test
    fun aFailedEnableDoesNotLeakItsMessageIntoTheProductResult() = runBlocking {
        runtime.enableResult = LocalLanProxyRuntimeOutcome.Answered(
            LocalLanProxyRuntimeResult(
                LocalLanProxyRuntimeResult.Status.ApplyFailed,
                message = "listen tcp 10.0.0.1:4321: bind: address already in use",
            )
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
    fun reconcileOwnerDiscardsADepartedOwnersRecordAndClosesTheirListener() = runBlocking {
        val facade = facade()
        facade.enable()
        assertTrue(facade.state.value.status is LocalLanProxyStatus.Active)

        settings.owner = "owner-b"
        facade.reconcileOwner()

        assertEquals(LocalLanProxyRuntimeState.Inactive, runtime.runtimeState)
        assertEquals(setOf("owner-b"), settings.recordOwners())
    }

    @Test
    fun reconcileOwnerKeepsTheRecordWhenTeardownIsNotAccountedFor() = runBlocking {
        val facade = facade()
        facade.enable()

        // A safety stop has confirmed nothing: the listener may still be
        // authenticating with those very credentials, and the record is the
        // only description of it left.
        runtime.disableResult = answered(LocalLanProxyRuntimeResult.Status.SafetyStop)
        settings.owner = "owner-b"
        facade.reconcileOwner()

        assertEquals(0, settings.discardCalls)
        assertTrue(settings.recordOwners().contains("owner-a"))

        // The next reconcile, once teardown can be confirmed, finishes the job.
        runtime.disableResult = answered(LocalLanProxyRuntimeResult.Status.Disabled)
        facade.reconcileOwner()

        assertEquals(setOf("owner-b"), settings.recordOwners())
    }

    @Test
    fun reconcileOwnerKeepsTheRecordWhenTheCommandNeverReachedTheRuntime() = runBlocking {
        val facade = facade()
        facade.enable()

        // Unbound, bind rejected, or the service died mid-call. On screen this
        // is the same sentence as "no VPN", but it is not evidence that the
        // departed owner's listener is gone.
        runtime.disableResult = LocalLanProxyRuntimeOutcome.TransportUnavailable
        settings.owner = "owner-b"
        facade.reconcileOwner()

        assertEquals(0, settings.discardCalls)
        assertTrue(settings.recordOwners().contains("owner-a"))
    }

    @Test
    fun aCommandThatNeverArrivedStillReadsAsVpnUnavailableOnScreen() = runBlocking {
        runtime.enableResult = LocalLanProxyRuntimeOutcome.TransportUnavailable

        assertEquals(LocalLanProxyResult.VpnUnavailable, facade().enable())
    }

    @Test
    fun reconcileOwnerDiscardsWhenThereIsNoSessionToHoldAListener() = runBlocking {
        val facade = facade()
        facade.refresh()

        runtime.disableResult = answered(LocalLanProxyRuntimeResult.Status.VpnUnavailable)
        settings.owner = "owner-b"
        facade.reconcileOwner()

        assertEquals(setOf("owner-b"), settings.recordOwners())
    }

    @Test
    fun reconcileOwnerLeavesStillOwnedSettingsAlone() = runBlocking {
        val facade = facade()
        facade.refresh()
        val mine = facade.state.value.config!!

        // A removal that failed: the account, and therefore the owner, is
        // exactly where it was.
        facade.reconcileOwner()

        assertEquals(0, settings.discardCalls)
        assertEquals(mine, settings.loadOrCreate())
    }

    @Test
    fun reconcileOwnerStillClosesTheListenerWhenTheRecordCannotBeRemoved() = runBlocking {
        val facade = facade()
        facade.enable()

        settings.owner = "owner-b"
        settings.discardable = false
        facade.reconcileOwner()

        assertEquals(LocalLanProxyRuntimeState.Inactive, runtime.runtimeState)
        assertEquals(LocalLanProxyStatus.Disabled, facade.state.value.status)
    }

    @Test
    fun settingsCannotBeEditedBeforeTheFirstReadEither() = runBlocking {
        // A screen opened onto a proxy this facade has not observed yet: the
        // snapshot still says Loading, the session says Active, and the
        // session is the one that decides.
        runtime.runtimeState = LocalLanProxyRuntimeState.Active("10.135.213.166", 4321)

        val facade = facade()
        val before = settings.loadOrCreate()

        val result = facade.updateConfig(LocalLanProxyUserConfig(4321, "someone", "another-secret"))

        assertEquals(LocalLanProxyResult.ActiveNotEditable, result)
        assertEquals(before, settings.loadOrCreate())

        // And the refusal leaves a coherent snapshot, not just a corrected
        // status: an Active proxy with no config would render as "settings
        // unavailable" about settings that are right there.
        val snapshot = facade.state.value
        assertEquals(LocalLanProxyStatus.Active("10.135.213.166", 4321), snapshot.status)
        assertEquals(before, snapshot.config)
        assertEquals(LocalLanProxyAvailability.Ready, snapshot.availability)
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
