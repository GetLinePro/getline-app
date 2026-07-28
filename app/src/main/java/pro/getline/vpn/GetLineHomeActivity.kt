package pro.getline.vpn

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import pro.getline.vpn.design.GetLineHomeDesign
import pro.getline.vpn.design.model.GetLineProductState
import pro.getline.vpn.design.model.GetLineRecoveryAction
import pro.getline.vpn.design.ui.ToastDuration
import pro.getline.vpn.getline.GetLineBackendProvider
import pro.getline.vpn.getline.GetLineBackendResult
import pro.getline.vpn.getline.GetLineSubscriptionDraft
import pro.getline.vpn.getline.GetLineSubscriptionId
import pro.getline.vpn.getline.GetLineSubscriptionType
import pro.getline.vpn.getline.LocalActiveRepair
import pro.getline.vpn.getline.VpnConfigurationRepairPolicy
import pro.getline.vpn.getline.accountportal.AccountPortalLaunchResult
import pro.getline.vpn.getline.accountportal.AccountPortalUriPolicy
import pro.getline.vpn.getline.accountportal.AccountPortalVisitCoordinator
import pro.getline.vpn.getline.accountportal.DefaultAccountPortalLauncher
import pro.getline.vpn.getline.accountportal.PendingForceSubscriptionRefresh
import pro.getline.vpn.getline.auth.GetLineSessionRepository
import pro.getline.vpn.getline.auth.GetLineSessionStore
import pro.getline.vpn.getline.auth.RwpGetLineAuthApi
import pro.getline.vpn.getline.auth.SubscriptionLoadResult
import pro.getline.vpn.getline.auth.SubscriptionPresentation
import pro.getline.vpn.getline.auth.SubscriptionStateHolder
import pro.getline.vpn.getline.auth.SubscriptionUiState
import pro.getline.vpn.getline.servers.ServerGroupingPolicy
import pro.getline.vpn.getline.servers.ServerNameParser
import pro.getline.vpn.getline.servers.VpnServerLoadResult
import pro.getline.vpn.getline.servers.VpnServerSelectionRepository
import pro.getline.vpn.getline.servers.VpnServerStateHolder
import pro.getline.vpn.getline.servers.VpnServerUiState
import pro.getline.vpn.common.util.ticker
import pro.getline.vpn.util.hasValidatedInternetConnection
import pro.getline.vpn.util.withClash
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pro.getline.vpn.design.R as DesignR

class GetLineHomeActivity : BaseActivity<GetLineHomeDesign>() {
    private val backend by lazy { GetLineBackendProvider.create(this) }
    private val sessionRepository by lazy {
        GetLineSessionRepository(
            api = RwpGetLineAuthApi(),
            store = GetLineSessionStore(this),
        )
    }
    private val serverSelectionRepository by lazy { VpnServerSelectionRepository() }
    /** Survives tab switches; cleared only when Activity is destroyed. */
    private val subscriptionState = SubscriptionStateHolder()
    private val serverState = VpnServerStateHolder()
    private val accountPortalLauncher = DefaultAccountPortalLauncher()
    private val accountPortalVisit = AccountPortalVisitCoordinator()
    /**
     * Serializes server list loads and patchSelector calls so overlapping taps and
     * resume reconcile cannot leave Mihomo/SelectionDao vs UI out of order.
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
    /**
     * When a force refresh is requested while [subscriptionState.requestInFlight] is true
     * (e.g. return from account portal during a manual refresh), run one more force load
     * after the in-flight request finishes so post-portal data is not dropped.
     */
    private val pendingForceSubscriptionRefresh = PendingForceSubscriptionRefresh()
    /**
     * Latest user-chosen server name while a patch or list load may still be in flight.
     * Prevents a slower load from overwriting a newer optimistic selection.
     */
    private var userSelectIntent: String? = null

    override suspend fun main() {
        val design = GetLineHomeDesign(this)

        setContentDesign(design)
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
                            userSelectIntent = null
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
                                connectionTimeout?.cancel()
                                connecting = false
                                backend.vpn.stop()
                            } else if (!connecting) {
                                design.startVpn()
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
                        GetLineHomeDesign.Request.AddSubscription,
                        GetLineHomeDesign.Request.SignIn ->
                            backend.navigation.openOnboarding()
                        // Not wired from product recovery UI. Kept as internal route only.
                        GetLineHomeDesign.Request.OpenProfiles ->
                            backend.navigation.openAdvanced()
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
                        GetLineHomeDesign.Request.RetrySubscription ->
                            design.refreshSubscriptionUi(force = true)
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
                    }
                }
                trafficTicker.onReceive {
                    design.refreshSession()
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
            setSession(sessionDurationMs = null, traffic = 0L)
            return
        }
        val snapshot = runCatching {
            withClash { querySessionDurationMs() to queryTrafficTotal() }
        }.getOrNull()
        setSession(
            sessionDurationMs = snapshot?.first,
            traffic = snapshot?.second ?: 0L,
        )
    }

    /**
     * Home product/VPN refresh only. Does not load /api/subscriptions for the
     * Subscription tab or mutate that destination state.
     *
     * Always runs [repairVpnConfiguration] before product state. Never opens
     * the CMFA profile picker.
     */
    private suspend fun GetLineHomeDesign.fetch(showLoading: Boolean) {
        if (refreshing)
            return

        refreshing = true
        setVpnStatus(resolveStatus())
        if (showLoading) {
            setProductState(GetLineProductState.PreparingVpn)
        }

        try {
            val repaired = repairVpnConfiguration(allowNetwork = showLoading)
            applyRepairOutcomeToProduct(repaired)
        } finally {
            refreshing = false
        }
    }

    private enum class RepairOutcome {
        Ready,
        NeedsSetup,
        /** Local binding present but local heal failed; Retry may remote. */
        FailedPrepare,
        /** Remote was required/attempted and failed (offline or API). */
        FailedRestore,
        BackendUnavailable,
    }

    /**
     * Idempotent repair ladder (Retry = this method, not "download again"):
     * 1) inspect + local setActive(managed) when imported
     * 2) remote re-provision only if managed profile proven absent and a path exists
     * 3) NeedsSetup when there is nothing to repair
     *
     * @param allowNetwork cold start / Retry may network; quiet resume stays local.
     */
    private suspend fun repairVpnConfiguration(allowNetwork: Boolean): RepairOutcome {
        val managedUuid = sessionRepository.managedProfileUuid()
        val hasSession = sessionRepository.hasSession()
        val savedSource = sessionRepository.managedProfileSource()
        val online = hasValidatedInternetConnection()

        val local = when (
            val result = backend.subscriptions.repairLocalActive(managedUuid)
        ) {
            GetLineBackendResult.Unavailable -> return RepairOutcome.BackendUnavailable
            is GetLineBackendResult.Success -> result.value
        }

        if (local is LocalActiveRepair.Ready) {
            return RepairOutcome.Ready
        }

        val absent = local as LocalActiveRepair.ManagedAbsent
        val step = VpnConfigurationRepairPolicy.plan(
            activeImportedUuid = null,
            managedUuid = managedUuid,
            managedIsImported = absent.managedIsImported,
            hasSession = hasSession,
            hasSavedUrlSource = savedSource != null,
            allowNetwork = allowNetwork,
            online = online,
        )

        return when (step) {
            VpnConfigurationRepairPolicy.Step.Done,
            VpnConfigurationRepairPolicy.Step.LocalActivate -> {
                // Local activate should have succeeded inside repairLocalActive.
                // One defensive retry if inventory said managed is imported.
                if (absent.managedIsImported) {
                    when (val again = backend.subscriptions.repairLocalActive(managedUuid)) {
                        GetLineBackendResult.Unavailable ->
                            return RepairOutcome.BackendUnavailable
                        is GetLineBackendResult.Success ->
                            if (again.value is LocalActiveRepair.Ready) {
                                return RepairOutcome.Ready
                            }
                    }
                }
                RepairOutcome.FailedPrepare
            }
            VpnConfigurationRepairPolicy.Step.NeedsSetup -> RepairOutcome.NeedsSetup
            VpnConfigurationRepairPolicy.Step.FailedLocalOnly -> RepairOutcome.FailedPrepare
            VpnConfigurationRepairPolicy.Step.OfflineForRemote -> RepairOutcome.FailedRestore
            VpnConfigurationRepairPolicy.Step.RemoteReprovision ->
                reProvisionManagedProfile(managedUuid)
        }
    }

    /**
     * Remote fallback only after local managed profile is proven absent.
     *
     * Provenance order (do not replace a custom/URL import with account preferred):
     * 1) [managedProfileSource] bound to this managed UUID
     * 2) else native session preferred subscription (account-managed installs)
     *
     * Always reuses [managedUuid] when present so Retry does not mint duplicates.
     */
    private suspend fun reProvisionManagedProfile(managedUuid: String?): RepairOutcome {
        val managedId = managedUuid?.let { GetLineSubscriptionId(it) }
        val boundSource = sessionRepository.managedProfileSource()

        val draft: GetLineSubscriptionDraft
        val subscriptionIdToRemember: String?

        if (boundSource != null) {
            draft = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = getString(DesignR.string.get_line_subscription_profile_name),
                source = boundSource,
            )
            // Keep existing subscription id; do not rewrite from preferred catalog.
            subscriptionIdToRemember = null
        } else if (sessionRepository.hasSession()) {
            val subscription = sessionRepository.loadPreferredSubscriptionOrNull()
                ?: return RepairOutcome.FailedRestore
            val source = subscription.subscriptionLink ?: return RepairOutcome.FailedRestore
            draft = GetLineSubscriptionDraft(
                type = GetLineSubscriptionType.Url,
                name = subscription.displayName
                    ?: getString(DesignR.string.get_line_subscription_profile_name),
                source = source,
            )
            subscriptionIdToRemember = subscription.id
        } else {
            return RepairOutcome.NeedsSetup
        }

        return when (
            val reimported = backend.subscriptions.reimportAndActivate(draft, managedId)
        ) {
            GetLineBackendResult.Unavailable -> RepairOutcome.FailedRestore
            is GetLineBackendResult.Success -> {
                sessionRepository.rememberManagedProfile(
                    uuid = reimported.value.value,
                    source = draft.source,
                )
                if (subscriptionIdToRemember != null) {
                    sessionRepository.rememberSubscription(subscriptionIdToRemember)
                }
                RepairOutcome.Ready
            }
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
     * Main group via [VpnServerSelectionRepository.queryMainSelectedName]
     * ([MainProxyGroupPolicy] — not silent first-in-list).
     */
    private suspend fun GetLineHomeDesign.refreshLocation() {
        if (!backend.vpn.running) {
            setLocation(null)
            return
        }
        val name = serverSelectionRepository.queryMainSelectedName(
            excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
            sort = uiStore.proxySort,
        )
        setLocation(name?.let { ServerNameParser.parse(it).displayQualifiedLabel })
    }

    /**
     * Ensure Servers UI is up to date without unnecessary Clash queries.
     * Used on tab select and initial open — not for forced retry after failure.
     */
    private fun GetLineHomeDesign.ensureServersLoaded() {
        if (!backend.vpn.running) {
            serverLoadJob?.cancel()
            userSelectIntent = null
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
            userSelectIntent = null
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
            userSelectIntent = null
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
                    serverSelectionRepository.loadMainGroup(
                        excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
                        sort = uiStore.proxySort,
                    )
                }
                if (!isActive) return@launch
                serverState.applyLoadResult(result)
                // A tap during load must win over the just-loaded core selection.
                val intent = userSelectIntent
                if (intent != null) {
                    serverState.applySelected(intent)
                }
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

        val measured = serversIoMutex.withLock {
            serverSelectionRepository.healthCheckMainGroup(
                excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
                sort = uiStore.proxySort,
            )
        }
        if (!measured || !isActive) return

        val refreshed = serversIoMutex.withLock {
            serverSelectionRepository.loadMainGroup(
                excludeNotSelectable = uiStore.proxyExcludeNotSelectable,
                sort = uiStore.proxySort,
            )
        }
        if (!isActive) return
        // Only delays changed; a failed re-read must not clobber a good list.
        if (refreshed !is VpnServerLoadResult.Success) return

        serverState.applyLoadResult(refreshed)
        userSelectIntent?.let { serverState.applySelected(it) }
        paintServersState()
    }

    private suspend fun GetLineHomeDesign.applyServerLocationFromState() {
        val ready = serverState.state as? VpnServerUiState.Ready
        if (ready != null) {
            // Home showed the raw name ("🇵🇱 Польша | grpc") while Servers showed
            // it parsed — same node, two spellings.
            setLocation(
                ready.selectedName
                    .takeIf { it.isNotBlank() }
                    ?.let { ServerNameParser.parse(it).displayQualifiedLabel },
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
     * runs under [serversIoMutex] and always applies [userSelectIntent] (latest tap)
     * so an earlier call cannot win after a later tap.
     */
    private fun GetLineHomeDesign.selectServer(name: String) {
        val ready = serverState.state as? VpnServerUiState.Ready ?: return
        if (!ready.selectable) return
        if (ready.selectedName == name && userSelectIntent == null) return
        if (!serverState.applySelected(name)) return

        userSelectIntent = name
        paintServersState()
        launch { setLocation(name) }

        launch {
            val ok = serversIoMutex.withLock {
                val current = serverState.state as? VpnServerUiState.Ready
                    ?: return@withLock false
                if (!current.selectable) return@withLock false
                val target = userSelectIntent ?: current.selectedName
                val success = serverSelectionRepository.select(current.groupName, target)
                if (success) {
                    if (userSelectIntent == target) {
                        userSelectIntent = null
                    }
                    // Align Ready only if no newer tap is pending.
                    if (userSelectIntent == null) {
                        serverState.applySelected(target)
                    }
                }
                success
            }
            if (!isActive) return@launch
            if (!ok) {
                userSelectIntent = null
                // Reconcile with Mihomo if patch failed.
                refreshServersUi(force = true)
            } else {
                paintServersState()
                applyServerLocationFromState()
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
                GetLineHomeDesign.ServersScreen.Ready(
                    currentDisplayName = current.ifBlank {
                        getString(DesignR.string.get_line_shell_location_unknown)
                    },
                    groups = groups.map { group ->
                        GetLineHomeDesign.ServerGroupRow(
                            key = group.key,
                            label = group.label,
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

    /**
     * Host resumed (onStart). Reconcile session cookie with holder without
     * always re-fetching a valid Ready card.
     *
     * Successful onboarding while this Activity stays alive:
     * SignedOut + session present → [SubscriptionStateHolder.invalidateSessionState]
     * → forced load → Ready/Empty/Failed. No profile import, no VPN restart.
     */
    private fun GetLineHomeDesign.onSubscriptionHostResumed() {
        val hasSession = sessionRepository.hasSession()
        when {
            !hasSession -> {
                // Session gone while away (logout / failed recovery elsewhere).
                // Drop pending portal-return refresh; do not touch browser cookies.
                accountPortalVisit.clear()
                pendingForceSubscriptionRefresh.clear()
                if (subscriptionState.state !is SubscriptionUiState.SignedOut) {
                    subscriptionLoadJob?.cancel()
                    subscriptionState.invalidateSessionState()
                    subscriptionState.applySignedOut(
                        hasImportedProfile = hasKnownImportedProfile,
                    )
                    paintSubscriptionState()
                } else {
                    // Still signed out — refresh profile flag only.
                    subscriptionState.applySignedOut(
                        hasImportedProfile = hasKnownImportedProfile,
                    )
                    paintSubscriptionState()
                }
            }
            subscriptionState.state is SubscriptionUiState.SignedOut -> {
                // Sign-in completed; invalidate and force load.
                subscriptionLoadJob?.cancel()
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
        val hasSession = sessionRepository.hasSession()
        if (!hasSession) {
            subscriptionLoadJob?.cancel()
            subscriptionState.applySignedOut(hasImportedProfile = hasKnownImportedProfile)
            paintSubscriptionState()
            return
        }

        if (!subscriptionState.needsInitialLoad()) {
            paintSubscriptionState()
            return
        }

        refreshSubscriptionUi(force = false)
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
        if (!sessionRepository.hasSession()) {
            pendingForceSubscriptionRefresh.clear()
            subscriptionState.applySignedOut(hasImportedProfile = hasKnownImportedProfile)
            paintSubscriptionState()
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

        paintSubscriptionState()
        subscriptionLoadJob?.cancel()
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
                                fallbackTitle = getString(DesignR.string.get_line_home_plan_unknown),
                            )
                        }
                        subscriptionState.applyLoadResult(result, presentation)
                        // API card can show renewed while Clash still has expire placeholders.
                        if (presentation?.isActive == true) {
                            refreshManagedProfileConfigAfterActiveSubscription()
                        }
                    }
                    SubscriptionLoadResult.SignedOut -> {
                        pendingForceSubscriptionRefresh.clear()
                        subscriptionState.applySignedOut(
                            hasImportedProfile = hasKnownImportedProfile,
                        )
                    }
                    SubscriptionLoadResult.TransientFailure -> {
                        subscriptionState.applyLoadResult(result, presentation = null)
                    }
                }
                paintSubscriptionState()
            } finally {
                if (!isActive) {
                    subscriptionState.onRequestCancelled()
                    // Cancelled jobs are often superseded by a newer load; do not start
                    // another force from here (avoids double-refresh races).
                } else if (
                    sessionRepository.hasSession() &&
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
     * Best-effort silent re-fetch of managed URL config after API says active.
     * No user-visible profile-update notifications; failures leave existing nodes.
     */
    private fun refreshManagedProfileConfigAfterActiveSubscription() {
        val managedUuid = sessionRepository.managedProfileUuid() ?: return
        launch {
            // Result ignored — ProfileChanged reloads Servers; Unavailable stays silent.
            backend.subscriptions.requestConfigUpdate(GetLineSubscriptionId(managedUuid))
        }
    }

    private fun GetLineHomeDesign.paintSubscriptionState() {
        launch {
            setSubscriptionScreen(subscriptionState.state.toScreen(this@paintSubscriptionState))
            setLogoutVisible(shouldShowLogout())
        }
    }

    /**
     * Logout is for device account/config clear — session tokens and/or managed binding.
     * Not shown when nothing GetLine-owned is present on the device.
     */
    private fun shouldShowLogout(): Boolean {
        return sessionRepository.hasSession() ||
            sessionRepository.managedProfileUuid() != null
    }

    /**
     * Product sign-out (after confirm):
     * 1) stop VPN including an in-progress start (not only [GetLineVpnController.running])
     * 2) clear native session tokens / managed binding — non-cancellable once confirmed
     * 3) best-effort delete managed profile only (must not block session clear)
     * 4) leave app settings and any non-managed profiles alone
     * 5) open onboarding
     */
    private suspend fun GetLineHomeDesign.performLogout() {
        if (loggingOut) return
        if (!shouldShowLogout()) {
            setLogoutVisible(false)
            return
        }
        if (!confirmLogout()) return

        loggingOut = true
        try {
            connectionTimeout?.cancel()
            // Always request stop: running may still be false while a start is in flight
            // (connecting=true, or service finishing after start() before CLASH_STARTED).
            connecting = false
            backend.vpn.stop()

            val managedUuid = sessionRepository.managedProfileUuid()

            // Confirmed logout: clear account state even if later work is cancelled
            // (rotation / Activity destroy during profile IPC).
            withContext(NonCancellable) {
                sessionRepository.logout()
                accountPortalVisit.clear()
                pendingForceSubscriptionRefresh.clear()
                subscriptionLoadJob?.cancel()
                subscriptionState.resetToLoading()
                hasKnownActiveProfile = false
                setHomeHasActiveProfile(false)
            }

            if (managedUuid != null) {
                // Best-effort; session is already cleared. Cancellation must not undo logout.
                try {
                    backend.subscriptions.deleteManaged(GetLineSubscriptionId(managedUuid))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Backend down / timeout — managed UUID may remain until next login reuse.
                }
            }

            backend.navigation.openOnboarding()
            finish()
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
                )
            is SubscriptionUiState.Failed ->
                GetLineHomeDesign.SubscriptionScreen.Failed
        }
    }

    private fun SubscriptionPresentation.toCard(
        design: GetLineHomeDesign,
    ): GetLineHomeDesign.CardContent {
        val expireText = expireAtEpochMillis
            ?.let { design.formatExpireUntil(it) }
            ?: getString(DesignR.string.get_line_home_expire_unknown)

        val trafficText = when {
            trafficUnlimited -> design.formatApiTraffic(0L, 0L, isUnlimited = true)
            trafficUsedBytes != null || trafficLimitBytes != null ->
                design.formatApiTraffic(
                    usedBytes = trafficUsedBytes ?: 0L,
                    limitBytes = trafficLimitBytes ?: 0L,
                    isUnlimited = false,
                )
            else -> getString(DesignR.string.get_line_home_traffic_unknown)
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
                    DesignR.string.get_line_home_status_active
                } else {
                    DesignR.string.get_line_home_status_inactive
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
            when (val repaired = repairVpnConfiguration(allowNetwork = true)) {
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
                val result = startActivityForResult(
                    ActivityResultContracts.StartActivityForResult(),
                    vpnRequest
                )

                if (result.resultCode == RESULT_OK) {
                    backend.vpn.start()
                    scheduleConnectionTimeout()
                } else {
                    connecting = false
                    setVpnStatus(resolveStatus())
                }
            } else {
                scheduleConnectionTimeout()
            }
        } catch (_: Exception) {
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
                    showToast(
                        DesignR.string.get_line_vpn_start_timeout,
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
     * Opens the web account portal in a Custom Tab (HTTPS app.getline.pro only).
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
                            DesignR.string.get_line_account_portal_open_failed_title,
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

    override fun onStopped(cause: String?) {
        events.trySend(Event.ClashStop)

        if (cause != null && activityStarted) {
            launch {
                design?.showToast(DesignR.string.get_line_vpn_stopped, ToastDuration.Long)
            }
        }
    }

    override fun shouldDisplayHomeAsUpEnabled(): Boolean {
        return false
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

        private const val CONNECTION_START_TIMEOUT_MS = 20_000L

        private const val TAB_HOME = "home"
        private const val TAB_SERVERS = "servers"
        private const val TAB_SUBSCRIPTION = "subscription"
    }
}
