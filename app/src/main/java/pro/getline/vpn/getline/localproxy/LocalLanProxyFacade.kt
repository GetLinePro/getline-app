package pro.getline.vpn.getline.localproxy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.compat.registerReceiverCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.localproxy.LocalLanProxyRuntimeState
import com.github.kr328.clash.service.remote.LocalLanProxyRuntimeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pro.getline.vpn.getline.auth.GetLineSessionStore
import java.util.UUID

/**
 * The one product surface of the local LAN proxy (see plan Module boundary).
 *
 * It is the only caller of the private runtime binder, the `StatusProvider`
 * projection and the change broadcast; the Settings screen sees [state] and
 * three suspend calls, and nothing else. Its job is to keep one coherent
 * snapshot: enabled state always comes from the running session — never from
 * a saved toggle or from what the last call returned — while configuration
 * comes from the encrypted store, so the screen can never show a stale address
 * beside a fresh status.
 *
 * Process-wide, not screen-scoped: [get] returns one instance, so rotating the
 * Settings activity or reopening it re-reads a live flow instead of a cached
 * call result.
 */
class LocalLanProxyFacade internal constructor(
    private val store: LocalLanProxySettings,
    private val runtime: LocalLanProxyRuntimeClient,
    private val vpnRunning: () -> Boolean,
    private val scope: CoroutineScope,
) : LocalLanProxy, LocalLanProxyOwnerIntegration {
    private val _state = MutableStateFlow(LocalLanProxySnapshot())

    override val state: StateFlow<LocalLanProxySnapshot> = _state.asStateFlow()

    /**
     * Serializes the transactions against each other and against the refresh
     * that follows them. Two enables from a double tap must not both reach the
     * runtime, and a refresh must not land between a transaction's result and
     * the snapshot it produces.
     */
    private val mutex = Mutex()

    /**
     * Set while a transaction is in flight, so an invalidation arriving
     * mid-transaction — the runtime publishes one the moment a listener binds —
     * does not replace `Enabling` with a status the user's own action is about
     * to supersede.
     */
    @Volatile
    private var inFlight: LocalLanProxyStatus? = null

    /**
     * Re-reads settings and the session's projection, and republishes the
     * snapshot. Serialized with the transactions: an invalidation that
     * overtook a finishing transaction would otherwise publish the state from
     * before it.
     */
    suspend fun refresh() = mutex.withLock { refreshLocked() }

    override suspend fun updateConfig(config: LocalLanProxyUserConfig): LocalLanProxyResult {
        LocalLanProxyConfigValidator.validate(config)?.let { return it }

        return mutex.withLock {
            // Hot-edit is forbidden: the active listener authenticates with the
            // credentials of its own transaction, and the probe that proves it
            // is GetLine's relies on those staying the same for the life of the
            // session. Saving new ones under a live listener would leave the
            // screen describing an endpoint that no longer accepts them.
            //
            // Asked of the session, not of the published snapshot: before the
            // first refresh the snapshot still says Loading, and a screen
            // opened onto an already-active proxy would otherwise be allowed
            // to overwrite its credentials.
            val runtimeState = runtime.state()
            if (runtimeState is LocalLanProxyRuntimeState.Active) {
                publish(_state.value.config, runtimeState)

                return@withLock LocalLanProxyResult.ActiveNotEditable
            }

            val saved = withContext(Dispatchers.IO) { store.save(config) }
            if (!saved) {
                return@withLock LocalLanProxyResult.SettingsUnavailable
            }

            publish(config, runtimeState)

            LocalLanProxyResult.Success
        }
    }

    override suspend fun enable(): LocalLanProxyResult = transaction(LocalLanProxyStatus.Enabling) {
        val config = withContext(Dispatchers.IO) { store.loadOrCreate() }
            ?: return@transaction LocalLanProxyResult.SettingsUnavailable

        LocalLanProxyConfigValidator.validate(config)?.let { return@transaction it }

        runtime.enable(config).toProductResult()
    }

    override suspend fun disable(): LocalLanProxyResult = transaction(LocalLanProxyStatus.Disabling) {
        runtime.disable().toProductResult()
    }

    /**
     * Confirmed account change (see [LocalLanProxyOwnerIntegration]).
     *
     * Fail closed, in this order: a listener bound under the departed owner is
     * torn down first, and their record is discarded only once that teardown
     * is accounted for. Doing it the other way round — or doing it anyway on
     * an unconfirmed teardown — would delete the only description of an
     * endpoint that is still accepting connections.
     *
     * Ownership that did not actually change is the common case — a removal
     * that failed, a sign-out that could not clear its binding — and it is
     * exactly the case that must not destroy still-owned settings, so it
     * returns without touching anything.
     */
    override suspend fun reconcileOwner() {
        if (!withContext(Dispatchers.IO) { store.belongsToAnotherOwner() }) return

        mutex.withLock {
            val teardown = runtime.disable().status

            // Discard only once the listener is accounted for. "Disabled" is a
            // confirmed teardown; "VpnUnavailable" means there is no session,
            // so there is no listener either. A safety stop or a failed apply
            // has confirmed nothing — the credentials may still authenticate —
            // and deleting the record there would destroy the only description
            // of an endpoint that is still up. The record stays, and the next
            // reconcile checks again.
            val accountedFor = teardown == LocalLanProxyRuntimeResult.Status.Disabled ||
                teardown == LocalLanProxyRuntimeResult.Status.VpnUnavailable

            if (!accountedFor) {
                Log.w("Local proxy owner reconcile kept a previous owner's settings: $teardown")
            } else if (!withContext(Dispatchers.IO) { store.discardForeignRecord() }) {
                Log.w("Local proxy settings of a previous owner could not be discarded")
            }

            refreshLocked()
        }
    }

    /**
     * Runs one transaction with [transitional] showing, then republishes from
     * the authoritative sources rather than from [block]'s result: what the
     * call returned and what the session actually holds can differ — a
     * fail-stop tears the session down after answering — and the session is
     * the one that decides.
     */
    private suspend fun transaction(
        transitional: LocalLanProxyStatus,
        block: suspend () -> LocalLanProxyResult,
    ): LocalLanProxyResult = mutex.withLock {
        inFlight = transitional
        _state.value = _state.value.copy(status = transitional)

        try {
            block()
        } finally {
            inFlight = null

            refreshLocked()
        }
    }

    private suspend fun refreshLocked() {
        publish(withContext(Dispatchers.IO) { store.loadOrCreate() }, runtime.state())
    }

    private fun publish(config: LocalLanProxyUserConfig?, runtimeState: LocalLanProxyRuntimeState) {
        val status = inFlight ?: when (runtimeState) {
            LocalLanProxyRuntimeState.Inactive -> LocalLanProxyStatus.Disabled
            is LocalLanProxyRuntimeState.Active ->
                LocalLanProxyStatus.Active(runtimeState.address, runtimeState.port)
        }

        _state.value = LocalLanProxySnapshot(
            status = status,
            availability = when {
                config == null -> LocalLanProxyAvailability.SettingsUnavailable
                !vpnRunning() -> LocalLanProxyAvailability.VpnOffline
                else -> LocalLanProxyAvailability.Ready
            },
            config = config,
        )
    }

    private fun LocalLanProxyRuntimeResult.toProductResult(): LocalLanProxyResult = when (status) {
        LocalLanProxyRuntimeResult.Status.Enabled,
        LocalLanProxyRuntimeResult.Status.Disabled,
        -> LocalLanProxyResult.Success
        LocalLanProxyRuntimeResult.Status.VpnUnavailable -> LocalLanProxyResult.VpnUnavailable
        LocalLanProxyRuntimeResult.Status.NoEligibleEndpoint -> LocalLanProxyResult.NoEligibleLan
        LocalLanProxyRuntimeResult.Status.PortOccupied -> LocalLanProxyResult.PortOccupied
        LocalLanProxyRuntimeResult.Status.ApplyFailed -> LocalLanProxyResult.ApplyFailed
        LocalLanProxyRuntimeResult.Status.SafetyStop -> LocalLanProxyResult.SafetyStop
    }

    companion object {
        @Volatile
        private var instance: LocalLanProxyFacade? = null

        /**
         * The process-wide instance, wired to production collaborators and
         * subscribed to the two things that can change its state behind the
         * user's back: the runtime's invalidation broadcast and the VPN
         * starting or stopping.
         */
        fun get(context: Context): LocalLanProxyFacade {
            instance?.let { return it }

            return synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }
        }

        private fun create(context: Context): LocalLanProxyFacade {
            val sessionStore = GetLineSessionStore(context)

            val facade = LocalLanProxyFacade(
                store = LocalLanProxySettingsStore(context) {
                    sessionStore.managedBindingSnapshot().managedProfileUuid
                },
                runtime = BinderLocalLanProxyRuntimeClient(context),
                vpnRunning = { Remote.broadcasts.clashRunning },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )

            facade.observe(context)

            return facade
        }
    }

    private fun observe(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // Invalidation only: the value is read back, never taken from
                // the intent.
                scope.launch { refresh() }
            }
        }

        context.registerReceiverCompat(
            receiver,
            IntentFilter(Intents.ACTION_LOCAL_LAN_PROXY_CHANGED),
            Permissions.RECEIVE_SELF_BROADCASTS,
        )

        // Availability, not proxy state: whether Enable is offered at all
        // changes with the tunnel, and the session teardown that follows a stop
        // publishes its own invalidation.
        Remote.broadcasts.addObserver(object : Broadcasts.Observer {
            override fun onServiceRecreated() = invalidate()
            override fun onStarted() = invalidate()
            override fun onStopped(cause: String?) = invalidate()
            override fun onProfileChanged() = Unit
            override fun onProfileUpdateCompleted(uuid: UUID?) = Unit
            override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) = Unit
            override fun onProfileLoaded() = Unit

            private fun invalidate() {
                scope.launch { refresh() }
            }
        })

        scope.launch { refresh() }
    }
}

/** Convenience for call sites that already have the application at hand. */
internal fun localLanProxy(): LocalLanProxy = LocalLanProxyFacade.get(Global.application)
