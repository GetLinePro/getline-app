package pro.getline.vpn

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import pro.getline.vpn.getlineui.GetLineHomeDesign
import pro.getline.vpn.getlineui.model.GetLineProductState
import pro.getline.vpn.getlineui.model.GetLineRecoveryAction
import pro.getline.vpn.getlineui.model.GetLineTraffic
import pro.getline.vpn.getlineui.ToastDuration
import pro.getline.vpn.getline.GetLineBackendProvider
import pro.getline.vpn.product.GetLineActivity
import pro.getline.vpn.getline.ConfigUpdateResult
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.LogoutFlow
import pro.getline.vpn.getline.VpnRepairFlow
import pro.getline.vpn.getline.VpnRepairFlow.RepairOutcome
import pro.getline.vpn.getline.accountportal.AccountPortalLaunchResult
import pro.getline.vpn.getline.accountportal.AccountPortalUriPolicy
import pro.getline.vpn.getline.accountportal.AccountPortalVisitCoordinator
import pro.getline.vpn.getline.accountportal.DefaultAccountPortalLauncher
import pro.getline.vpn.getline.accountportal.PendingForceSubscriptionRefresh
import pro.getline.vpn.diagnostics.DiagnosticReportShare
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.GetLineSessionStorageException
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi
import pro.getline.vpn.getline.auth.LinkOnlyPresentation
import pro.getline.vpn.getline.auth.SubscriptionLoadResult
import pro.getline.vpn.getline.auth.SubscriptionPresentation
import pro.getline.vpn.getline.auth.SubscriptionStateHolder
import pro.getline.vpn.getline.auth.SubscriptionUiState
import pro.getline.vpn.getline.GetLineSubscriptionSummary
import com.github.kr328.clash.HelpActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.ticker
import java.util.concurrent.atomic.AtomicBoolean
import pro.getline.vpn.getline.servers.ServerGroupingPolicy
import pro.getline.vpn.getline.servers.ServerLocationLabel
import pro.getline.vpn.getline.servers.ServerSection
import pro.getline.vpn.getline.servers.ServerSectionPolicy
import pro.getline.vpn.getline.servers.VpnServerLoadResult
import pro.getline.vpn.getline.servers.VpnServerStateHolder
import pro.getline.vpn.getline.servers.VpnServerUiState
import pro.getline.vpn.util.hasValidatedInternetConnection
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.github.kr328.clash.design.R as DesignR
import pro.getline.vpn.getlineui.R as GetLineUiR

class GetLineHomeActivity : GetLineActivity<GetLineHomeDesign>() {
    private val backend by lazy { GetLineBackendProvider.create(this) }
    private val sessionStore by lazy { GetLineSessionStore(this) }
    private val sessionRepository by lazy {
        GetLineSessionRepository(
            api = RwpGetLineAuthApi(),
            store = sessionStore,
        )
    }
    private val vpnRepairFlow by lazy {
        VpnRepairFlow(
            backend = backend,
            sessionRepository = sessionRepository,
            host = object : VpnRepairFlow.Host {
                override fun hasValidatedInternetConnection(): Boolean =
                    this@GetLineHomeActivity.hasValidatedInternetConnection()

                override fun defaultProfileName(): String =
                    getString(GetLineUiR.string.get_line_subscription_profile_name)
            },
        )
    }
    /** Survives tab switches; cleared only when Activity is destroyed. */
    private val subscriptionState = SubscriptionStateHolder()
    private val serverState = VpnServerStateHolder()
    private val accountPortalLauncher = DefaultAccountPortalLauncher()
    private val accountPortalVisit = AccountPortalVisitCoordinator()
    /**
     * Serializes server list loads and patchSelector calls so overlapping taps and
     * resume reconcile cannot leave Mihomo/SelectionDao vs UI out of order.
     * Latency health checks deliberately stay outside: they may re-pick a nested
     * dynamic group's leaf but do not write the main selector's own choice, can
     * take seconds, and must not delay a user selection behind them.
     */
    private val serversIoMutex = Mutex()
    private var connecting = false
    private var refreshing = false
    /** Guards concurrent Logout confirmations / teardown. */
    private var loggingOut = false
    private var backendUnavailable = false
    private var hasKnownActiveProfile = false
    /** Any imported CMFA profile (active or not) — for Subscription signed-out copy. */
    private var hasKnownImportedProfile = false
    private var connectionTimeout: Job? = null
    private var subscriptionLoadJob: Job? = null
    private var serverLoadJob: Job? = null
    /** Bug 3 diagnostic once per process — remove after saveSession question is closed. */
    private val subscriptionConsistencyLogged = AtomicBoolean(false)
    /**
     * When a force refresh is requested while [subscriptionState.requestInFlight] is true
     * (e.g. return from account portal during a manual refresh), run one more force load
     * after the in-flight request finishes so post-portal data is not dropped.
     */
    private val pendingForceSubscriptionRefresh = PendingForceSubscriptionRefresh()
    override suspend fun main() {
        val design = GetLineHomeDesign(this)

        setContentDesign(design)
        val sessionStorageRecovered = try {
            sessionStore.recoveredFromStorageFailure
        } catch (_: GetLineSessionStorageException) {
            backend.navigation.openOnboarding()
            if (!isFinishing) finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_SESSION_STORAGE_RECOVERED, false) ||
            sessionStorageRecovered
        ) {
            design.showToast(
                GetLineUiR.string.get_line_state_session_storage_recovered_explanation,
                ToastDuration.Long,
            )
        }
        design.setTab(readPersistedTab())
        design.setVpnStatus(resolveStatus())
        if (intent.getBooleanExtra(EXTRA_BACKEND_UNAVAILABLE, false)) {
            backendUnavailable = true
            design.setProductState(GetLineProductState.BackendUnavailable)
        } else {
            design.fetch(showLoading = true)
        }
        // If shell opens on Subscription/Servers, load once without waiting for a re-tap.
        if (design.selectedTab == GetLineHomeDesign.Tab.Subscription) {
            design.ensureSubscriptionLoaded()
        }
        if (design.selectedTab == GetLineHomeDesign.Tab.Servers) {
            design.ensureServersLoaded()
        }

        val trafficTicker = ticker(TimeUnit.SECONDS.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart -> {
                            // Portal return: exactly one forced subscription refresh when the
                            // host actually stopped after a successful Custom Tab launch.
                            // Active sub also schedules managed URL config update (Servers nodes).
                            // Does not restart VPN or change server/tab.
                            val refreshAfterPortal = accountPortalVisit.onHostStarted()
                            if (refreshAfterPortal) {
                                // Re-enable portal CTA; error cleared on successful leave/return.
                                design.setAccountPortalUi(
                                    visible = shouldShowAccountPortalCta(),
                                    launching = false,
                                    showError = false,
                                )
                            }
                            if (!backendUnavailable) {
                                design.fetch(showLoading = false)
                            } else {
                                design.refreshLocation()
                            }
                            // After onboarding/sign-in (or logout elsewhere): sync session vs UI.
                            // Does not re-import profile or touch VPN.
                            design.onSubscriptionHostResumed()
                            if (refreshAfterPortal && sessionRepository.hasSession()) {
                                // Force load after real portal return. If a refresh is already
                                // in flight, queue one more force load (see pendingForce).
                                design.refreshSubscriptionUi(force = true)
                            }
                            // Selector may change in legacy ProxyActivity without lifecycle events.
                            // Always re-query Mihomo; do not trust Ready cache after resume.
                            design.reconcileServersOnResume()
                        }
                        Event.ActivityStop -> {
                            accountPortalVisit.onHostStopped()
                        }
                        Event.ProfileLoaded,
                        Event.ProfileChanged -> {
                            design.fetch(showLoading = false)
                            // Proxy groups may change with profile; drop cached server list.
                            serverState.invalidate()
                            if (design.selectedTab == GetLineHomeDesign.Tab.Servers) {
                                design.refreshServersUi(force = true)
                            }
                        }
                        Event.ClashStart -> {
                            // Observed state (may be external start), not always from our click.
                            Log.i("vpn_state value=started")
                            connectionTimeout?.cancel()
                            connecting = false
                            design.fetch(showLoading = false)
                            if (design.selectedTab == GetLineHomeDesign.Tab.Servers) {
                                design.refreshServersUi(force = true)
                            } else {
                                serverState.invalidate()
                            }
                        }
                        Event.ClashStop,
                        Event.ServiceRecreated -> {
                            // Observed state — not a causal "disconnect succeeded".
                            Log.i(
                                "vpn_state value=" +
                                    if (it == Event.ServiceRecreated) {
                                        "service_recreated"
                                    } else {
                                        "stopped"
                                    },
                            )
                            connectionTimeout?.cancel()
                            connecting = false
                            if (it == Event.ServiceRecreated || !backendUnavailable) {
                                design.fetch(showLoading = false)
                            } else {
                                design.setVpnStatus(resolveStatus())
                                design.refreshLocation()
                            }
                            // List needs live core; show stopped without opening Proxy UI.
                            serverLoadJob?.cancel()
                            serverState.applyVpnStopped()
                            design.paintServersState()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        GetLineHomeDesign.Request.ToggleVpn -> {
                            if (backend.vpn.running) {
                                Log.i("vpn_ui action=disconnect_clicked")
                                connectionTimeout?.cancel()
                                connecting = false
                                backend.vpn.stop()
                            } else if (!connecting) {
                                Log.i("vpn_ui action=connect_clicked")
                                design.startVpn()
                            } else {
                                // Tap during in-flight connect — not "user never pressed".
                                Log.i("vpn_ui action=connect_ignored reason=connecting")
                            }
                        }
                        GetLineHomeDesign.Request.SelectHome ->
                            persistTab(GetLineHomeDesign.Tab.Home)
                        GetLineHomeDesign.Request.SelectServers -> {
                            persistTab(GetLineHomeDesign.Tab.Servers)
                            // Show cached list; fetch only when not yet loaded.
                            // Does not run latency tests or open legacy ProxyActivity.
                            design.ensureServersLoaded()
                        }
                        GetLineHomeDesign.Request.SelectSubscription -> {
                            persistTab(GetLineHomeDesign.Tab.Subscription)
                            // Show cached state; fetch only when not yet loaded.
                            design.ensureSubscriptionLoaded()
                        }
                        GetLineHomeDesign.Request.Retry ->
                            design.fetch(showLoading = true)
                        GetLineHomeDesign.Request.AddSubscription ->
                            backend.navigation.openOnboarding()
                        // Sign-in on top of a working link-only subscription is an
                        // optional upgrade, not a fallback to the entry screen: keep
                        // Home alive so the user can come back to the running VPN.
                        // The screen also drops QR / manual import there — the
                        // subscription already exists.
                        GetLineHomeDesign.Request.SignIn ->
                            if (usesLinkOnlyUi() &&
                                sessionRepository.managedProfileUuid() != null
                            ) {
                                backend.navigation.openLinkOnlySignIn()
                            } else {
                                backend.navigation.openOnboarding()
                            }
                        /**
                         * Home recovery (e.g. SubscriptionExpired) must not launch the
                         * web portal — that entry lives only on the Subscription tab.
                         * Send the user there so they can use OpenAccountPortal.
                         */
                        GetLineHomeDesign.Request.OpenAccount -> {
                            design.setTab(GetLineHomeDesign.Tab.Subscription)
                            persistTab(GetLineHomeDesign.Tab.Subscription)
                            design.ensureSubscriptionLoaded()
                        }
                        GetLineHomeDesign.Request.OpenAccountPortal ->
                            design.openAccountPortal()
                        GetLineHomeDesign.Request.RefreshSubscription,
                        GetLineHomeDesign.Request.RetrySubscription -> {
                            if (usesLinkOnlyUi()) {
                                design.refreshLinkOnlySubscription()
                            } else {
                                design.refreshSubscriptionUi(force = true)
                            }
                        }
                        GetLineHomeDesign.Request.RetryServers ->
                            design.refreshServersUi(force = true)
                        GetLineHomeDesign.Request.SelectServer -> {
                            val name = design.consumePendingServerName()
                            if (name != null) {
                                design.selectServer(name)
                            }
                        }
                        GetLineHomeDesign.Request.Logout ->
                            design.performLogout()
                        GetLineHomeDesign.Request.OpenHelp ->
                            startActivity(HelpActivity::class.intent)
                        GetLineHomeDesign.Request.SendDiagnostics ->
                            DiagnosticReportShare.present(
                                activity = this@GetLineHomeActivity,
                                hasSession = sessionRepository.hasSession(),
                            )
                    }
                }
                // The rendezvous ticker blocks without a receiver, so a stopped
                // Activity neither polls the service nor updates its hidden UI.
                if (activityStarted) {
                    trafficTicker.onReceive {
                        design.refreshSession()
                    }
                }
            }
        }
    }

    /**
     * Session clock and byte counter inside the connect ring.
     *
     * Both come from the service that owns the tunnel, so reopening the screen
     * continues an existing session instead of restarting the count. Zeroed
     * while stopped so a stale figure does not read as live.
     */
    private suspend fun GetLineHomeDesign.refreshSession() {
        if (!backend.vpn.running) {
            setSession(sessionDurationMs = null, traffic = GetLineTraffic.Zero)
            return
        }
        val session = backend.vpn.querySession()
        setSession(
            sessionDurationMs = session?.durationMs,
            traffic = session?.traffic ?: GetLineTraffic.Zero,
        )
    }

    /**
     * Home product/VPN refresh only. Does not load /api/subscriptions for the
     * Subscription tab or mutate that destination state.
     *
     * Always runs [VpnRepairFlow.repairVpnConfiguration] before product state. Never opens
     * the CMFA profile picker.
     * [showLoading] controls PreparingVpn only; [allowNetwork] controls whether
     * the repair ladder may re-provision a missing managed profile.
     */
    private suspend fun GetLineHomeDesign.fetch(
        showLoading: Boolean,
        allowNetwork: Boolean = showLoading,
    ) {
        if (refreshing)
            return

        refreshing = true
        setVpnStatus(resolveStatus())
        if (showLoading) {
            setProductState(GetLineProductState.PreparingVpn)
        }

        try {
            val repaired = vpnRepairFlow.repairVpnConfiguration(allowNetwork = allowNetwork)
            applyRepairOutcomeToProduct(repaired)
        } finally {
            refreshing = false
        }
    }

    private suspend fun GetLineHomeDesign.applyRepairOutcomeToProduct(outcome: RepairOutcome) {
        when (outcome) {
            RepairOutcome.BackendUnavailable -> {
                backendUnavailable = true
                setProductState(GetLineProductState.BackendUnavailable)
                refreshLocation()
                return
            }
            RepairOutcome.NeedsSetup -> {
                hasKnownActiveProfile = false
                setHomeHasActiveProfile(false)
                setLocation(null)
                setProductState(
                    GetLineProductState.NoProfile,
                    GetLineRecoveryAction.ImportSubscription,
                )
                // Still refresh imported flag for Subscription tab copy.
                refreshSnapshotFlags()
                return
            }
            RepairOutcome.FailedPrepare -> {
                hasKnownActiveProfile = false
                setHomeHasActiveProfile(false)
                setLocation(null)
                setProductState(
                    GetLineProductState.ConnectionRepairFailed,
                    GetLineRecoveryAction.Retry,
                )
                refreshSnapshotFlags()
                return
            }
            RepairOutcome.FailedRestore -> {
                hasKnownActiveProfile = false
                setHomeHasActiveProfile(false)
                setLocation(null)
                setProductState(
                    GetLineProductState.ConnectionRestoreFailed,
                    GetLineRecoveryAction.Retry,
                )
                refreshSnapshotFlags()
                return
            }
            RepairOutcome.Ready -> Unit
        }

        when (val loaded = backend.subscriptions.snapshot()) {
            is GetLineBackendResult.Success -> {
                backendUnavailable = false
                val snapshot = loaded.value
                val active = snapshot.active
                hasKnownActiveProfile = active != null
                hasKnownImportedProfile = snapshot.hasImported
                setHomeHasActiveProfile(hasKnownActiveProfile)

                if (active == null) {
                    setLocation(null)
                    // Ready from repair but snapshot lag — treat as prepare fail.
                    setProductState(
                        GetLineProductState.ConnectionRepairFailed,
                        GetLineRecoveryAction.Retry,
                    )
                } else {
                    val online = hasValidatedInternetConnection()
                    setProductState(
                        if (online) {
                            GetLineProductState.Content
                        } else {
                            GetLineProductState.Offline
                        }
                    )
                    refreshLocation()
                }
            }
            GetLineBackendResult.Unavailable -> {
                backendUnavailable = true
                setProductState(GetLineProductState.BackendUnavailable)
                refreshLocation()
            }
        }
    }

    private suspend fun refreshSnapshotFlags() {
        when (val loaded = backend.subscriptions.snapshot()) {
            is GetLineBackendResult.Success -> {
                hasKnownImportedProfile = loaded.value.hasImported
                hasKnownActiveProfile = loaded.value.active != null
            }
            GetLineBackendResult.Unavailable -> Unit
        }
    }

    private fun resolveStatus(): GetLineHomeDesign.VpnStatus {
        return when {
            backend.vpn.running -> GetLineHomeDesign.VpnStatus.Connected
            connecting -> GetLineHomeDesign.VpnStatus.Connecting
            else -> GetLineHomeDesign.VpnStatus.Disconnected
        }
    }

    /**
     * Current selector group selection for Home/Servers location row.
     * Best-effort; failures yield unknown without affecting VPN.
     * Main group via [VpnServerSelectionRepository.queryMainSelection]
     * ([MainProxyGroupPolicy] — not silent first-in-list).
     */
    private suspend fun GetLineHomeDesign.refreshLocation() {
        if (!backend.vpn.running) {
            setLocation(null)
            return
        }
        val selection = backend.servers.queryMainSelection()
        setLocation(ServerLocationLabel.of(selection?.selectedName, selection?.resolvedName))
    }

    /**
     * Leaf a selected nested group routes through, from the list already loaded.
     * Same value [VpnServerSelectionRepository.queryMainSelection] would return,
     * without a second trip to the core — so both paths paint the same row.
     */
    private fun VpnServerUiState.Ready.resolvedNameOf(rawName: String): String? =
        servers.firstOrNull { it.name == rawName && it.isGroup }?.resolvedName

    /**
     * Ensure Servers UI is up to date without unnecessary Clash queries.
     * Used on tab select and initial open — not for forced retry after failure.
     */
    private fun GetLineHomeDesign.ensureServersLoaded() {
        if (!backend.vpn.running) {
            serverLoadJob?.cancel()
            serverState.applyVpnStopped()
            paintServersState()
            return
        }

        if (!serverState.needsInitialLoad()) {
            paintServersState()
            return
        }

        refreshServersUi(force = false)
    }

    /**
     * Re-read main-group selection from Mihomo after Activity becomes visible.
     * Required because ProxyActivity patchSelector does not emit Profile/Clash events.
     */
    private fun GetLineHomeDesign.reconcileServersOnResume() {
        if (!backend.vpn.running) {
            serverLoadJob?.cancel()
            serverState.applyVpnStopped()
            paintServersState()
            return
        }

        if (!serverState.beginReconcile()) {
            // Load already in flight — its result will be current enough.
            return
        }

        // Paint Loading only when we left Ready (beginReconcile keeps Ready visible).
        if (serverState.state is VpnServerUiState.Loading) {
            paintServersState()
        }

        startServerLoadJob()
    }

    /**
     * Load main VPN group proxies into [serverState].
     * Does not run health checks, open ProxyActivity, or restart VPN.
     */
    private fun GetLineHomeDesign.refreshServersUi(force: Boolean) {
        if (!backend.vpn.running) {
            serverLoadJob?.cancel()
            serverState.applyVpnStopped()
            paintServersState()
            return
        }

        val started = if (force) {
            serverState.beginRefresh()
        } else {
            serverState.beginInitialLoad()
        }
        if (!started) {
            paintServersState()
            return
        }

        paintServersState()
        startServerLoadJob()
    }

    private fun GetLineHomeDesign.startServerLoadJob() {
        serverLoadJob?.cancel()
        serverLoadJob = launch {
            try {
                val result = serversIoMutex.withLock {
                    backend.servers.loadMainGroup()
                }
                if (!isActive) return@launch
                serverState.applyLoadResult(result)
                paintServersState()
                applyServerLocationFromState()
                measureServerDelays()
            } finally {
                if (!isActive) {
                    serverState.onRequestCancelled()
                }
            }
        }
    }

    /**
     * Measure latency once per interval, then re-read the group so the ranking
     * and the per-row delays reflect it. Best-effort: on failure the list stays
     * exactly as loaded, just without delays.
     */
    private suspend fun GetLineHomeDesign.measureServerDelays() {
        if (serverState.state !is VpnServerUiState.Ready) return

        val now = SystemClock.elapsedRealtime()
        if (!serverState.shouldHealthCheck(now)) return
        serverState.onHealthCheckStarted(now)

        // A probe may take several seconds and may re-pick the leaf of a nested
        // dynamic group, but it does not write the main selector's own choice.
        // Keeping it under serversIoMutex would make a user tap wait before
        // patchSelector can run and before Servers can return to Home.
        val measured = backend.servers.healthCheckMainGroup()
        if (!measured || !isActive) return

        val refreshed = serversIoMutex.withLock {
            backend.servers.loadMainGroup()
        }
        if (!isActive) return
        // A failed re-read must not clobber a good list.
        if (refreshed !is VpnServerLoadResult.Success) return

        serverState.applyLoadResult(refreshed)
        paintServersState()
        // Not only delays: a dynamic group re-picks its leaf off exactly these
        // measurements, so the Home row would otherwise name the previous node
        // until the next resume.
        applyServerLocationFromState()
    }

    private suspend fun GetLineHomeDesign.applyServerLocationFromState() {
        val ready = serverState.state as? VpnServerUiState.Ready
        if (ready != null) {
            // Home showed the raw name ("🇵🇱 Польша | grpc") while Servers showed
            // it parsed — same node, two spellings.
            setLocation(
                ServerLocationLabel.of(
                    ready.selectedName,
                    ready.resolvedNameOf(ready.selectedName),
                ),
            )
        } else {
            refreshLocation()
        }
    }

    /**
     * Same selection command as ProxyActivity: patchSelector on the main group.
     * Does not stop VPN or open legacy Proxy UI.
     *
     * Overlapping taps: optimistic UI updates immediately; Binder patchSelector
     * runs under [serversIoMutex]. [VpnServerStateHolder.Selection.generation]
     * prevents an older success/failure from settling or clearing a newer tap.
     */
    private fun GetLineHomeDesign.selectServer(name: String) {
        val ready = serverState.state as? VpnServerUiState.Ready ?: return
        if (!ready.selectable) return
        if (serverState.isSelectionConfirmed(name)) {
            returnToHomeAfterServerSelection()
            return
        }
        if (serverState.beginSelection(name) == null) return

        paintServersState()
        // Optimistic row uses the same formatting as the settled one, so a
        // confirmed pick does not visibly respell itself.
        launch { setLocation(ServerLocationLabel.of(name, ready.resolvedNameOf(name))) }

        launch {
            // Any queued worker may service the latest intent. A later worker sees
            // no pending selection after it was settled and exits without a duplicate patch.
            val attempted = serversIoMutex.withLock {
                val selection = serverState.pendingSelection ?: return@withLock null
                val current = serverState.state as? VpnServerUiState.Ready
                    ?: return@withLock selection to false
                if (!current.selectable) return@withLock selection to false
                selection to backend.servers.select(current.groupName, selection.name)
            } ?: return@launch
            if (!isActive) return@launch

            when (serverState.completeSelection(attempted.first, attempted.second)) {
                VpnServerStateHolder.SelectionCompletion.Stale -> Unit
                VpnServerStateHolder.SelectionCompletion.LatestFailure -> {
                    // Reconcile with Mihomo only when the failed command still owns
                    // the latest intent. Stale failure leaves the newer tap alone.
                    refreshServersUi(force = true)
                }
                VpnServerStateHolder.SelectionCompletion.LatestSuccess -> {
                    paintServersState()
                    applyServerLocationFromState()
                    returnToHomeAfterServerSelection()
                }
            }
        }
    }

    private fun GetLineHomeDesign.paintServersState() {
        launch {
            setServersScreen(serverState.state.toScreen())
        }
    }

    private fun VpnServerUiState.toScreen(): GetLineHomeDesign.ServersScreen {
        return when (this) {
            is VpnServerUiState.Loading ->
                GetLineHomeDesign.ServersScreen.Loading
            is VpnServerUiState.Ready -> {
                val current = servers.firstOrNull { it.name == selectedName }?.displayName
                    ?: selectedName
                val groups = ServerGroupingPolicy.group(
                    servers = servers,
                    selectedRawName = selectedName,
                    preferredByGroup = serverState.preferences,
                )
                val headings = ServerSectionPolicy.headings(groups.map { it.section })
                GetLineHomeDesign.ServersScreen.Ready(
                    currentDisplayName = current.ifBlank {
                        getString(GetLineUiR.string.get_line_shell_location_unknown)
                    },
                    groups = groups.mapIndexed { index, group ->
                        GetLineHomeDesign.ServerGroupRow(
                            key = group.key,
                            label = group.label,
                            sectionLabel = headings[index]?.let { getString(sectionLabelRes(it)) },
                            variantLabel = group.primaryVariantLabel,
                            protocol = group.primaryProtocol,
                            delayMs = group.primaryDelayMs,
                            primaryName = group.primaryRawName,
                            selected = group.selected,
                            resolvedLabel = group.resolvedLabel,
                            variants = group.variants.map { variant ->
                                GetLineHomeDesign.ServerRow(
                                    name = variant.rawName,
                                    displayName = variant.label,
                                    selected = variant.selected,
                                    delayMs = variant.delayMs,
                                    protocol = variant.protocol,
                                    activeViaGroup = variant.activeViaGroup,
                                )
                            },
                        )
                    },
                    selectable = selectable,
                )
            }
            is VpnServerUiState.Empty ->
                GetLineHomeDesign.ServersScreen.Empty
            is VpnServerUiState.VpnStopped ->
                GetLineHomeDesign.ServersScreen.VpnStopped
            is VpnServerUiState.Failed ->
                GetLineHomeDesign.ServersScreen.Failed
        }
    }

    private fun sectionLabelRes(section: ServerSection): Int = when (section) {
        ServerSection.Main -> GetLineUiR.string.get_line_servers_section_main
        ServerSection.Lte -> GetLineUiR.string.get_line_servers_section_lte
        ServerSection.Youtube -> GetLineUiR.string.get_line_servers_section_youtube
    }

    /**
     * Host resumed (onStart). Reconcile session cookie with holder without
     * always re-fetching a valid Ready card.
     *
     * Successful onboarding while this Activity stays alive:
     * SignedOut + real account session → [SubscriptionStateHolder.invalidateSessionState]
     * → forced load → Ready/Empty/Failed. No profile import, no VPN restart.
     *
     * Mixed post-login (session + still-link-only binding) is treated as link-only
     * UI — see [usesLinkOnlyUi].
     */
    private fun GetLineHomeDesign.onSubscriptionHostResumed() {
        when {
            usesLinkOnlyUi() -> {
                // No usable account card: pure signed-out, or session exists but the
                // post-login step never finished (still link-only binding).
                // Drop pending portal-return refresh; do not touch browser cookies.
                accountPortalVisit.clear()
                pendingForceSubscriptionRefresh.clear()
                when (subscriptionState.state) {
                    is SubscriptionUiState.Ready,
                    is SubscriptionUiState.Empty,
                    is SubscriptionUiState.Failed -> {
                        // Drop session-bound card immediately (not after snapshot IPC).
                        cancelSubscriptionJob()
                        subscriptionState.invalidateSessionState()
                        paintSubscriptionState()
                        scheduleApplySignedOutState()
                    }
                    is SubscriptionUiState.SignedOut,
                    is SubscriptionUiState.Loading -> {
                        // Keep in-flight link-only refresh / apply; do not silent-cancel.
                        if (subscriptionState.requestInFlight ||
                            subscriptionLoadJob?.isActive == true
                        ) {
                            paintSubscriptionState()
                        } else {
                            scheduleApplySignedOutState()
                        }
                    }
                }
            }
            subscriptionState.state is SubscriptionUiState.SignedOut -> {
                // Sign-in completed and binding is account-bound; invalidate and force load.
                cancelSubscriptionJob()
                subscriptionState.invalidateSessionState()
                paintSubscriptionState()
                refreshSubscriptionUi(force = true)
            }
            selectedTab == GetLineHomeDesign.Tab.Subscription -> {
                // Already signed-in with stable state — paint cache; load only if needed.
                ensureSubscriptionLoaded()
            }
            else -> Unit
        }
    }

    /**
     * Ensure Subscription UI is up to date without unnecessary network calls.
     * Used on tab select and initial open — not for post-login invalidation
     * (see [onSubscriptionHostResumed]).
     */
    private fun GetLineHomeDesign.ensureSubscriptionLoaded() {
        if (usesLinkOnlyUi()) {
            // Do not cancel an in-flight link-only refresh when switching tabs.
            if (subscriptionState.requestInFlight ||
                subscriptionLoadJob?.isActive == true
            ) {
                paintSubscriptionState()
                return
            }
            scheduleApplySignedOutState()
            return
        }

        if (!subscriptionState.needsInitialLoad()) {
            paintSubscriptionState()
            return
        }

        refreshSubscriptionUi(force = false)
    }

    /** Async inventory → SignedOut (+ optional link-only card), then paint. */
    private fun GetLineHomeDesign.scheduleApplySignedOutState() {
        subscriptionLoadJob = launch {
            applySignedOutState()
            paintSubscriptionState()
        }
    }

    /**
     * Cancel the subscription load job without letting its finally clear a
     * superseding request (generation bump happens on the next begin/apply).
     */
    private fun cancelSubscriptionJob() {
        val job = subscriptionLoadJob
        subscriptionLoadJob = null
        job?.cancel()
    }

    /**
     * Build SignedOut (with optional link-only card) from local profile snapshot.
     * Card only when active imported uuid matches managed binding.
     *
     * Transient profile-backend failure ([GetLineBackendResult.Unavailable]) must
     * not erase an existing link-only card — only a successful snapshot can prove
     * the managed profile is missing or not active.
     */
    private suspend fun applySignedOutState() {
        val managed = sessionRepository.managedProfileUuid()
        if (managed == null) {
            subscriptionState.applySignedOut(
                hasImportedProfile = hasKnownImportedProfile,
                linkOnly = null,
            )
            return
        }
        when (val snap = snapshotActiveSummary()) {
            is GetLineBackendResult.Success -> {
                val linkOnly = snap.value
                    ?.takeIf { it.uuid == managed }
                    ?.let { LinkOnlyPresentation.fromSummary(it) }
                subscriptionState.applySignedOut(
                    hasImportedProfile = hasKnownImportedProfile,
                    linkOnly = linkOnly,
                )
            }
            GetLineBackendResult.Unavailable -> {
                val previousLinkOnly =
                    (subscriptionState.state as? SubscriptionUiState.SignedOut)?.linkOnly
                subscriptionState.applySignedOut(
                    hasImportedProfile = hasKnownImportedProfile,
                    linkOnly = previousLinkOnly,
                )
            }
        }
    }

    /**
     * Snapshot active imported summary; updates [hasKnownImportedProfile] /
     * [hasKnownActiveProfile] on success (same side effect as [refreshSnapshotFlags]).
     *
     * [GetLineBackendResult.Success] with null active means the inventory was read
     * and there is no active imported profile. [GetLineBackendResult.Unavailable]
     * is a transient IPC/timeout failure — not proof of absence.
     */
    private suspend fun snapshotActiveSummary(): GetLineBackendResult<GetLineSubscriptionSummary?> {
        return when (val loaded = backend.subscriptions.snapshot()) {
            is GetLineBackendResult.Success -> {
                hasKnownImportedProfile = loaded.value.hasImported
                hasKnownActiveProfile = loaded.value.active != null
                GetLineBackendResult.Success(loaded.value.active)
            }
            GetLineBackendResult.Unavailable -> GetLineBackendResult.Unavailable
        }
    }

    /**
     * Load or refresh /api/subscriptions into [subscriptionState].
     *
     * When the preferred subscription is active, also silently re-fetches the
     * managed URL profile so Servers pick up provider node list changes
     * (renew, plan/pool change — not only expire placeholders). Uses in-process
     * update (no ProfileWorker result notifications). [Event.ProfileChanged]
     * reloads the server list. Does not restart VPN.
     *
     * Force requests that arrive while a load is already in flight are queued
     * via [pendingForceSubscriptionRefresh] and run once after completion so a
     * portal-return refresh is not silently dropped.
     */
    private fun GetLineHomeDesign.refreshSubscriptionUi(force: Boolean) {
        if (usesLinkOnlyUi()) {
            pendingForceSubscriptionRefresh.clear()
            // Not a silent no-op: rebuild SignedOut (stub or link-only) and paint.
            if (subscriptionState.requestInFlight ||
                subscriptionLoadJob?.isActive == true
            ) {
                // Prefer not to cancel link-only refresh mid-flight; paint current.
                paintSubscriptionState()
                return
            }
            scheduleApplySignedOutState()
            return
        }

        val started = if (force) {
            subscriptionState.beginRefresh()
        } else {
            subscriptionState.beginInitialLoad()
        }
        if (!started) {
            if (force) {
                // Keep one pending force load (portal return / retry while busy).
                pendingForceSubscriptionRefresh.mark()
            }
            // Parallel press or already in flight — keep current paint.
            paintSubscriptionState()
            return
        }

        val generation = subscriptionState.flightGeneration
        paintSubscriptionState()
        cancelSubscriptionJob()
        subscriptionLoadJob = launch {
            try {
                val result = sessionRepository.loadSubscriptionForUi()
                if (!isActive) return@launch

                when (result) {
                    is SubscriptionLoadResult.Success -> {
                        val preferred = result.preferred
                        val presentation = preferred?.let {
                            SubscriptionPresentation.fromPreferred(
                                item = it,
                                fallbackTitle = getString(GetLineUiR.string.get_line_home_plan_unknown),
                            )
                        }
                        subscriptionState.applyLoadResult(
                            result = result,
                            presentation = presentation,
                            generation = generation,
                        )
                        Log.i(
                            "subscription_ui load ok preferred=${preferred != null} " +
                                "active=${presentation?.isActive}",
                        )
                        // API card can show renewed while Clash still has expire placeholders.
                        if (presentation?.isActive == true) {
                            refreshManagedProfileConfigAfterActiveSubscription()
                        }
                    }
                    SubscriptionLoadResult.SignedOut -> {
                        pendingForceSubscriptionRefresh.clear()
                        applySignedOutState()
                        Log.i("subscription_ui load signed_out")
                    }
                    SubscriptionLoadResult.TransientFailure -> {
                        subscriptionState.applyLoadResult(
                            result = result,
                            presentation = null,
                            generation = generation,
                        )
                        Log.w(
                            "subscription_ui load failed " +
                                "state=${subscriptionState.state::class.simpleName}",
                        )
                    }
                }
                paintSubscriptionState()
            } finally {
                if (!isActive) {
                    subscriptionState.onRequestCancelled(generation)
                    // Cancelled jobs are often superseded by a newer load; do not start
                    // another force from here (avoids double-refresh races).
                } else if (
                    !usesLinkOnlyUi() &&
                    pendingForceSubscriptionRefresh.consume()
                ) {
                    // Drain at most one queued force refresh after a normal completion.
                    // Portal return while a Ready refresh was busy relies on this.
                    refreshSubscriptionUi(force = true)
                }
            }
        }
    }

    /**
     * Re-fetch managed URL profile config for link-only (no account session).
     * Keeps the SignedOut card visible; failures surface as transient error text.
     *
     * When there is no managed binding (or begin is rejected), rebuilds SignedOut
     * and paints — never a silent no-op for Refresh/Retry.
     */
    private fun GetLineHomeDesign.refreshLinkOnlySubscription() {
        val managed = sessionRepository.managedProfileUuid()
        if (managed == null) {
            // No managed binding (e.g. 401 cleared session+uuid): rebuild SignedOut, not no-op.
            cancelSubscriptionJob()
            scheduleApplySignedOutState()
            return
        }
        // Parallel press while refresh is already in flight.
        if (subscriptionState.requestInFlight) {
            paintSubscriptionState()
            return
        }
        // Drop a non-flight inventory job (if any) before starting refresh.
        cancelSubscriptionJob()
        val generation = subscriptionState.beginLinkOnlyRefresh()
        if (generation == null) {
            scheduleApplySignedOutState()
            return
        }
        paintSubscriptionState()
        subscriptionLoadJob = launch {
            try {
                val updated = backend.subscriptions
                    .requestConfigUpdate(GetLineSubscriptionId(managed))
                if (!isActive) return@launch
                val previousLinkOnly =
                    (subscriptionState.state as? SubscriptionUiState.SignedOut)?.linkOnly
                when (val snap = snapshotActiveSummary()) {
                    is GetLineBackendResult.Success -> {
                        val summary = snap.value?.takeIf { it.uuid == managed }
                        subscriptionState.applyLinkOnlyRefreshResult(
                            linkOnly = summary?.let(LinkOnlyPresentation::fromSummary),
                            // Clear card only when inventory confirms managed is gone;
                            // update failure alone keeps whatever snapshot returned.
                            // Unlike account-backed active state, link-only does not
                            // recreate a profile the local inventory says was removed.
                            failed = updated == ConfigUpdateResult.Unavailable,
                            generation = generation,
                        )
                    }
                    GetLineBackendResult.Unavailable -> {
                        // Snapshot failed: keep last known card; treat as refresh failure.
                        subscriptionState.applyLinkOnlyRefreshResult(
                            linkOnly = previousLinkOnly,
                            failed = true,
                            generation = generation,
                        )
                    }
                }
                paintSubscriptionState()
            } finally {
                if (!isActive) {
                    subscriptionState.onRequestCancelled(generation)
                }
            }
        }
    }

    /**
     * Re-fetch managed URL config after the account API says it is active.
     * Updated stays silent; NotFound is a consistency fault and runs the existing
     * network repair, applying its product state. Other outcomes preserve current state.
     */
    private fun GetLineHomeDesign.refreshManagedProfileConfigAfterActiveSubscription() {
        val managedUuid = sessionRepository.managedProfileUuid() ?: return
        launch {
            when (
                backend.subscriptions.requestConfigUpdate(
                    GetLineSubscriptionId(managedUuid),
                )
            ) {
                ConfigUpdateResult.NotFound -> {
                    fetch(showLoading = false, allowNetwork = true)
                }
                ConfigUpdateResult.Updated,
                ConfigUpdateResult.NotRefreshable,
                ConfigUpdateResult.Unavailable -> Unit
            }
        }
    }

    private fun GetLineHomeDesign.paintSubscriptionState() {
        launch {
            // Bug 3 diagnostic (once per process): remove after saveSession question is closed.
            if (subscriptionConsistencyLogged.compareAndSet(false, true)) {
                val hasSession = sessionRepository.hasSession()
                Log.i(
                    "subscription_ui has_refresh=$hasSession " +
                        "verdict=${sessionRepository.consistencyVerdict()} " +
                        "state=${subscriptionState.state::class.simpleName}",
                )
                // Mixed post-login (session + still-link-only) legitimately paints SignedOut.
                if (
                    hasSession &&
                    subscriptionState.state is SubscriptionUiState.SignedOut &&
                    !sessionRepository.needsPostLoginSubscriptionStep()
                ) {
                    Log.w("subscription_ui inconsistent SignedOut while has_refresh=true")
                }
            }
            setSubscriptionScreen(subscriptionState.state.toScreen(this@paintSubscriptionState))
            setAccountAction(accountAction())
        }
    }

    /**
     * Session exists but the post-login step never finished: the active profile is
     * still the link-only one, so the tab must describe it, not the account.
     *
     * Exit path already exists: link-only «Войти для управления» opens Onboarding,
     * which reads [GetLineSessionRepository.needsPostLoginSubscriptionStep] and
     * resumes the mismatch dialog. No persisted flag and no new navigation.
     */
    private fun usesLinkOnlyUi(): Boolean =
        !sessionRepository.hasSession() ||
            sessionRepository.needsPostLoginSubscriptionStep()

    private fun accountAction(): GetLineHomeDesign.AccountAction = when {
        // Real account session only. Mixed post-login still has tokens but must
        // show RemoveSubscription — SignOut would clear the link-only binding via
        // logout() under the wrong label (and delete the working profile).
        !usesLinkOnlyUi() -> GetLineHomeDesign.AccountAction.SignOut
        sessionRepository.managedProfileUuid() != null ->
            GetLineHomeDesign.AccountAction.RemoveSubscription
        else -> GetLineHomeDesign.AccountAction.None
    }

    /**
     * Product sign-out / remove-subscription (after confirm):
     * 1) stop VPN including an in-progress start (not only [GetLineVpnController.running])
     * 2) fence process-scoped import before reading the managed binding
     * 3) let [LogoutFlow] apply the action-specific session/delete order
     * 4) apply its result to Home or open onboarding
     */
    private suspend fun GetLineHomeDesign.performLogout() {
        if (loggingOut) return
        val action = accountAction()
        if (action == GetLineHomeDesign.AccountAction.None) {
            setAccountAction(GetLineHomeDesign.AccountAction.None)
            return
        }
        if (!confirmLogout(action)) return

        loggingOut = true
        try {
            connectionTimeout?.cancel()
            connecting = false
            val flowAction = when (action) {
                GetLineHomeDesign.AccountAction.SignOut -> LogoutFlow.Action.SignOut
                GetLineHomeDesign.AccountAction.RemoveSubscription ->
                    LogoutFlow.Action.RemoveSubscription
                GetLineHomeDesign.AccountAction.None -> return
            }
            val outcome = LogoutFlow(
                backend = backend,
                sessionRepository = sessionRepository,
                host = object : LogoutFlow.Host {
                    override suspend fun onSessionCleared() {
                        accountPortalVisit.clear()
                        pendingForceSubscriptionRefresh.clear()
                        cancelSubscriptionJob()
                        subscriptionState.resetToLoading()
                        hasKnownActiveProfile = false
                        setHomeHasActiveProfile(false)
                    }
                },
            ).perform(flowAction)

            when (outcome) {
                LogoutFlow.Outcome.RemoveSubscriptionFailed -> {
                    // Session + binding remain, so the same Remove action can retry.
                    paintSubscriptionState()
                }
                LogoutFlow.Outcome.SignOutFailed -> {
                    // Tokens are gone but the binding remains addressable as link-only.
                    scheduleApplySignedOutState()
                    fetch(showLoading = false)
                }
                LogoutFlow.Outcome.Completed -> {
                    backend.navigation.openOnboarding()
                    // openOnboarding finishes the caller; keep finish() for safety if policy changes.
                    if (!isFinishing) {
                        finish()
                    }
                    return
                }
            }
            showToast(
                GetLineUiR.string.get_line_remove_subscription_failed,
                ToastDuration.Long,
            )
        } finally {
            loggingOut = false
        }
    }

    private fun SubscriptionUiState.toScreen(
        design: GetLineHomeDesign,
    ): GetLineHomeDesign.SubscriptionScreen {
        return when (this) {
            is SubscriptionUiState.Loading ->
                GetLineHomeDesign.SubscriptionScreen.Loading
            is SubscriptionUiState.Ready ->
                GetLineHomeDesign.SubscriptionScreen.Ready(
                    card = subscription.toCard(design),
                    isRefreshing = isRefreshing,
                    transientError = transientError,
                )
            is SubscriptionUiState.Empty ->
                GetLineHomeDesign.SubscriptionScreen.Empty
            is SubscriptionUiState.SignedOut ->
                GetLineHomeDesign.SubscriptionScreen.SignedOut(
                    hasImportedProfile = hasImportedProfile,
                    card = linkOnly?.toCard(design),
                    isRefreshing = isRefreshing,
                    refreshFailed = refreshFailed,
                )
            is SubscriptionUiState.Failed ->
                GetLineHomeDesign.SubscriptionScreen.Failed
        }
    }

    private fun LinkOnlyPresentation.toCard(
        design: GetLineHomeDesign,
    ): GetLineHomeDesign.CardContent {
        val expireText = expireAtEpochMillis
            ?.let { design.formatExpireUntil(it) }
            ?: getString(GetLineUiR.string.get_line_home_expire_unknown)

        val trafficText = when {
            trafficUsedBytes != null || trafficLimitBytes != null ->
                design.formatApiTraffic(
                    usedBytes = trafficUsedBytes ?: 0L,
                    limitBytes = trafficLimitBytes ?: 0L,
                    isUnlimited = false,
                )
            else -> getString(GetLineUiR.string.get_line_home_traffic_unknown)
        }

        val limitBytes = trafficLimitBytes?.takeIf { it > 0L }
        val trafficUsedFraction = if (limitBytes == null) {
            null
        } else {
            ((trafficUsedBytes ?: 0L).toDouble() / limitBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

        return GetLineHomeDesign.CardContent(
            title = getString(GetLineUiR.string.get_line_subscription_link_only_title),
            isActive = false,
            statusText = null,
            expireText = expireText,
            daysLeft = null,
            trafficText = trafficText,
            trafficUsedFraction = trafficUsedFraction,
            devicesText = null,
        )
    }

    private fun SubscriptionPresentation.toCard(
        design: GetLineHomeDesign,
    ): GetLineHomeDesign.CardContent {
        val expireText = expireAtEpochMillis
            ?.let { design.formatExpireUntil(it) }
            ?: getString(GetLineUiR.string.get_line_home_expire_unknown)

        val trafficText = when {
            trafficUnlimited -> design.formatApiTraffic(0L, 0L, isUnlimited = true)
            trafficUsedBytes != null || trafficLimitBytes != null ->
                design.formatApiTraffic(
                    usedBytes = trafficUsedBytes ?: 0L,
                    limitBytes = trafficLimitBytes ?: 0L,
                    isUnlimited = false,
                )
            else -> getString(GetLineUiR.string.get_line_home_traffic_unknown)
        }

        // Only a real allowance gives the bar a whole to draw a part of. An
        // unlimited or unreported plan gets no bar rather than a made-up one.
        val limitBytes = trafficLimitBytes?.takeIf { it > 0L }
        val trafficUsedFraction = if (trafficUnlimited || limitBytes == null) {
            null
        } else {
            ((trafficUsedBytes ?: 0L).toDouble() / limitBytes.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

        val devicesText = deviceLimit
            ?.let { design.formatDeviceLimit(it) }

        return GetLineHomeDesign.CardContent(
            title = title,
            isActive = isActive,
            statusText = getString(
                if (isActive) {
                    GetLineUiR.string.get_line_home_status_active
                } else {
                    GetLineUiR.string.get_line_home_status_inactive
                }
            ),
            expireText = expireText,
            daysLeft = daysLeft,
            trafficText = trafficText,
            trafficUsedFraction = trafficUsedFraction,
            devicesText = devicesText,
        )
    }

    private suspend fun GetLineHomeDesign.startVpn() {
        val hasActiveProfile = if (backendUnavailable && hasKnownActiveProfile) {
            true
        } else {
            setProductState(GetLineProductState.PreparingVpn)
            when (val repaired = vpnRepairFlow.repairVpnConfiguration(allowNetwork = true)) {
                RepairOutcome.BackendUnavailable -> {
                    backendUnavailable = true
                    setProductState(GetLineProductState.BackendUnavailable)
                    return
                }
                RepairOutcome.Ready -> {
                    hasKnownActiveProfile = true
                    setHomeHasActiveProfile(true)
                    true
                }
                RepairOutcome.NeedsSetup -> {
                    hasKnownActiveProfile = false
                    setHomeHasActiveProfile(false)
                    setProductState(
                        GetLineProductState.NoProfile,
                        GetLineRecoveryAction.ImportSubscription,
                    )
                    return
                }
                RepairOutcome.FailedPrepare -> {
                    hasKnownActiveProfile = false
                    setHomeHasActiveProfile(false)
                    setProductState(
                        GetLineProductState.ConnectionRepairFailed,
                        GetLineRecoveryAction.Retry,
                    )
                    return
                }
                RepairOutcome.FailedRestore -> {
                    hasKnownActiveProfile = false
                    setHomeHasActiveProfile(false)
                    setProductState(
                        GetLineProductState.ConnectionRestoreFailed,
                        GetLineRecoveryAction.Retry,
                    )
                    return
                }
            }
        }

        if (!hasActiveProfile) {
            setProductState(
                GetLineProductState.ConnectionRepairFailed,
                GetLineRecoveryAction.Retry,
            )
            return
        }

        connecting = true
        setVpnStatus(resolveStatus())

        try {
            val vpnRequest = backend.vpn.start()

            if (vpnRequest != null) {
                Log.i("vpn_start stage=permission_needed")
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    Log.i("vpn_start stage=permission_result result=ok")
                    // Second start may still return a consent intent — do not claim requested.
                    val afterGrant = backend.vpn.start()
                    if (afterGrant != null) {
                        Log.w("vpn_start stage=permission_still_needed path=after_permission")
                        connecting = false
                        setVpnStatus(resolveStatus())
                    } else {
                        Log.i("vpn_start stage=requested path=after_permission")
                        scheduleConnectionTimeout()
                    }
                } else {
                    Log.i("vpn_start stage=permission_result result=denied")
                    connecting = false
                    setVpnStatus(resolveStatus())
                }
            } else {
                Log.i("vpn_start stage=requested path=direct")
                scheduleConnectionTimeout()
            }
        } catch (e: CancellationException) {
            // Rotate/destroy while VPN consent dialog is open — not a start failure.
            throw e
        } catch (e: Exception) {
            // kind only — Exception.message can carry paths/config.
            Log.w("vpn_start stage=failed kind=${e::class.simpleName}")
            connectionTimeout?.cancel()
            connecting = false
            setVpnStatus(resolveStatus())
            showToast(DesignR.string.unable_to_start_vpn, ToastDuration.Long)
        }
    }

    private fun GetLineHomeDesign.scheduleConnectionTimeout() {
        connectionTimeout?.cancel()
        connectionTimeout = launch {
            delay(CONNECTION_START_TIMEOUT_MS)

            if (connecting) {
                connecting = false
                setVpnStatus(resolveStatus())

                if (!backend.vpn.running) {
                    Log.w("vpn_start stage=timeout")
                    showToast(
                        GetLineUiR.string.get_line_vpn_start_timeout,
                        ToastDuration.Long,
                    )
                }
            }
        }
    }

    private fun shouldShowAccountPortalCta(): Boolean {
        return when (subscriptionState.state) {
            is SubscriptionUiState.Ready,
            is SubscriptionUiState.Empty,
            is SubscriptionUiState.Failed -> true
            is SubscriptionUiState.Loading,
            is SubscriptionUiState.SignedOut -> false
        }
    }

    /**
     * Opens the web account portal in a Custom Tab (HTTPS portal host for this flavor).
     * Does not pass native tokens, use WebView, or import profiles / touch VPN.
     */
    private fun GetLineHomeDesign.openAccountPortal() {
        if (!accountPortalVisit.canLaunch()) {
            return
        }

        val showCta = shouldShowAccountPortalCta()
        val uri = try {
            AccountPortalUriPolicy.dashboardUri()
        } catch (_: Exception) {
            launch {
                setAccountPortalUi(visible = showCta, launching = false, showError = showCta)
            }
            return
        }

        if (showCta) {
            launch {
                setAccountPortalUi(visible = true, launching = true, showError = false)
            }
        }

        val result = accountPortalLauncher.open(this@GetLineHomeActivity, uri)
        when (result) {
            AccountPortalLaunchResult.Launched -> {
                accountPortalVisit.onLaunched()
                // Keep button disabled until return lifecycle clears visit.
                if (showCta) {
                    launch {
                        setAccountPortalUi(visible = true, launching = true, showError = false)
                    }
                }
            }
            AccountPortalLaunchResult.AlreadyInProgress -> {
                // VisitCoordinator already owns the lock; keep UI quiet.
            }
            AccountPortalLaunchResult.NoBrowserAvailable,
            AccountPortalLaunchResult.RejectedUri,
            is AccountPortalLaunchResult.Failed -> {
                accountPortalVisit.onLaunchFailed()
                if (showCta) {
                    launch {
                        setAccountPortalUi(visible = true, launching = false, showError = true)
                    }
                } else {
                    launch {
                        showToast(
                            GetLineUiR.string.get_line_account_portal_open_failed_title,
                            ToastDuration.Long,
                        )
                    }
                }
            }
        }
    }

    private fun readPersistedTab(): GetLineHomeDesign.Tab {
        return when (uiStore.getLineShellTab) {
            TAB_SERVERS -> GetLineHomeDesign.Tab.Servers
            TAB_SUBSCRIPTION -> GetLineHomeDesign.Tab.Subscription
            else -> GetLineHomeDesign.Tab.Home
        }
    }

    private fun persistTab(tab: GetLineHomeDesign.Tab) {
        uiStore.getLineShellTab = when (tab) {
            GetLineHomeDesign.Tab.Home -> TAB_HOME
            GetLineHomeDesign.Tab.Servers -> TAB_SERVERS
            GetLineHomeDesign.Tab.Subscription -> TAB_SUBSCRIPTION
        }
    }

    /**
     * Horizontal swipe between the three destinations. Every event is still
     * delivered normally, so vertical scrolling and taps are untouched; the
     * design only watches the stream and reports a completed fling.
     *
     * On such a fling the up event is swapped for a cancel before it reaches
     * the view tree. Without that the view under the finger — a server row is
     * full width, so the finger never leaves its bounds — would also perform
     * its click, selecting a server or toggling the VPN behind the swipe. That
     * holds for a swipe off the first or last tab too, where nothing changes
     * on screen and a stray click would be the only visible result.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val consumed = design?.onHostTouchEvent(ev) ?: false
        if (!consumed || ev.actionMasked != MotionEvent.ACTION_UP) {
            return super.dispatchTouchEvent(ev)
        }
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        return try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    /**
     * Back: non-Home tab → Home; Home → leave shell.
     * Does not stop VPN.
     */
    override fun handleBackPressed() {
        val design = design
        if (design != null && design.selectedTab != GetLineHomeDesign.Tab.Home) {
            design.setTab(GetLineHomeDesign.Tab.Home)
            persistTab(GetLineHomeDesign.Tab.Home)
            return
        }
        super.handleBackPressed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requestPermissionLauncher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        internal const val EXTRA_BACKEND_UNAVAILABLE =
            "pro.getline.vpn.extra.GET_LINE_BACKEND_UNAVAILABLE"
        internal const val EXTRA_SESSION_STORAGE_RECOVERED =
            "pro.getline.vpn.extra.GET_LINE_SESSION_STORAGE_RECOVERED"

        private const val CONNECTION_START_TIMEOUT_MS = 20_000L

        private const val TAB_HOME = "home"
        private const val TAB_SERVERS = "servers"
        private const val TAB_SUBSCRIPTION = "subscription"
    }
}
