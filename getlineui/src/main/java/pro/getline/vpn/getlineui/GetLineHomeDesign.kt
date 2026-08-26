package pro.getline.vpn.getlineui

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.card.MaterialCardView
import pro.getline.vpn.getlineui.databinding.DesignGetLineHomeBinding
import pro.getline.vpn.getlineui.view.GetLineConnectRingView
import pro.getline.vpn.getlineui.model.GetLineProductState
import pro.getline.vpn.getlineui.model.GetLineRecoveryAction
import pro.getline.vpn.getlineui.model.GetLineTraffic
import pro.getline.vpn.getlineui.util.formatTotal
import pro.getline.vpn.getlineui.util.toBytesString
import pro.getline.vpn.getlineui.util.toDateStr
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

class GetLineHomeDesign(context: Context) : GetLineScreen<GetLineHomeDesign.Request>(context) {
    enum class Request {
        ToggleVpn,
        SelectHome,
        SelectServers,
        SelectSubscription,
        Retry,
        AddSubscription,
        OpenAccount,
        /**
         * Open GetLine web account portal (Custom Tab) from Subscription CTA.
         * Not native checkout; does not create native session.
         */
        OpenAccountPortal,
        RefreshSubscription,
        /** Share the current subscription URL (QR + copy). Not account export. */
        ShareSubscription,
        /** Retry Subscription destination load (not Home product-state retry). */
        RetrySubscription,
        /** Open existing onboarding/auth from Subscription signed-out. */
        SignIn,
        /** Retry Servers destination load. */
        RetryServers,
        /** Manual server-list refresh from the Servers header icon. */
        RefreshServers,
        /**
         * Select a proxy by Mihomo name.
         * Payload is [pendingServerName] (enum keeps Java data-binding happy).
         */
        SelectServer,
        /**
         * Sign out of GetLine on this device (confirm first in design).
         * Stops VPN and removes managed GetLine config — not app settings.
         */
        Logout,
        /** Open existing HelpActivity (support links, about). */
        OpenHelp,
        /** Home row → app list and routing mode (legacy AccessControlActivity). */
        OpenAppRouting,
        /** GL-19: local safe diagnostic report → preview → share. */
        SendDiagnostics,
    }

    /** Payload for [Request.SelectServer]; cleared when the request is consumed. */
    var pendingServerName: String? = null
        private set

    enum class VpnStatus {
        Disconnected,
        Connecting,
        Connected,
    }

    /**
     * What the Home routing row shows. Mirrors the service-side access control
     * mode without importing it: this module does not depend on :service, and the
     * host maps between the two.
     */
    enum class AppRoutingMode {
        All,
        OnlySelected,
        ExceptSelected,
    }

    /**
     * Shell destinations. Home and Subscription are the shell-bar tabs
     * ([SHELL_TABS]); Servers is a detail of Home with no tab of its own.
     */
    enum class Tab {
        Home,
        Servers,
        Subscription,
    }

    /**
     * Subscription card content (Subscription destination only).
     * Managed profiles are rendered from their saved local snapshot.
     */
    data class CardContent(
        /** Tariff label. Null hides the tariff row text. */
        val title: String?,
        val isActive: Boolean,
        val statusText: String? = null,
        val expireText: String,
        val daysLeft: Int? = null,
        val trafficText: String,
        /**
         * Share of the plan's byte allowance already spent, 0..1. Null when the
         * plan is unlimited or reports no limit — there is no whole to show a
         * part of, and a bar drawn anyway would be inventing one.
         */
        val trafficUsedFraction: Float? = null,
        val devicesText: String? = null,
    )

    /**
     * Visual model for the Subscription destination (no network DTOs).
     */
    sealed interface SubscriptionScreen {
        data object Loading : SubscriptionScreen

        data class Ready(
            val card: CardContent,
            val isRefreshing: Boolean = false,
            val transientError: Boolean = false,
        ) : SubscriptionScreen

        data object Empty : SubscriptionScreen

        data object Failed : SubscriptionScreen
    }

    /**
     * Bottom-of-Subscription account action: sign out, remove link-only profile, or hide.
     */
    enum class AccountAction {
        None,
        SignOut,
        RemoveSubscription,
    }

    /**
     * Visual model for the Servers destination (no Clash DTOs).
     */
    sealed interface ServersScreen {
        data object Loading : ServersScreen

        data class Ready(
            val currentDisplayName: String,
            val groups: List<ServerGroupRow>,
            val selectable: Boolean,
            val showLatencyPrerequisite: Boolean = false,
        ) : ServersScreen

        data object Empty : ServersScreen

        data object Failed : ServersScreen
    }

    /**
     * A country row. Tapping it selects [primaryName]; the chevron expands
     * [variants]. A group with a single variant has nothing to expand.
     *
     * [sectionLabel] is a heading rendered above this row. The host sets it on the
     * first row of each section and leaves it null everywhere else, so a list with
     * one section carries no headings at all.
     */
    data class ServerGroupRow(
        val key: String,
        val label: String,
        val sectionLabel: String? = null,
        val variantLabel: String?,
        val protocol: String?,
        val delayMs: Int?,
        val primaryName: String,
        val selected: Boolean,
        /** Leaf a nested group ("⚡ Авто") currently routes through. */
        val resolvedLabel: String?,
        val showLatencyProgress: Boolean = false,
        val variants: List<ServerRow>,
    ) {
        val expandable: Boolean
            get() = variants.size > 1
    }

    data class ServerRow(
        val name: String,
        val displayName: String,
        val selected: Boolean,
        val delayMs: Int? = null,
        val protocol: String? = null,
        /** Running via a nested group rather than picked directly. */
        val activeViaGroup: Boolean = false,
        val showLatencyProgress: Boolean = false,
    )

    private val binding = DesignGetLineHomeBinding
        .inflate(layoutInflater, contentRoot, false)

    override val root: View
        get() = binding.root

    private var productState = GetLineProductState.Loading
    private var vpnStatus = VpnStatus.Disconnected
    private var hasProfile = false
    private var currentTab = Tab.Home
    private var accountPortalVisible = false
    private var accountPortalLaunching = false
    private var accountPortalErrorVisible = false

    /** Country groups the user opened. UI-only; reset when a group disappears. */
    private val expandedGroupKeys = mutableSetOf<String>()

    /** Session traffic for the connect ring; product bytes, not CMFA packed Traffic. */
    private var sessionTraffic: GetLineTraffic = GetLineTraffic.Zero
    private var sessionDurationMs: Long? = null

    /** Latest rendered server list, so expanding cannot resurrect a stale one. */
    private var lastServerGroups: List<ServerGroupRow> = emptyList()
    private var lastServersSelectable: Boolean = false

    /**
     * Set by [swipeDetector] while [onHostTouchEvent] is running, read right
     * after. A fling is reported from inside `GestureDetector.onTouchEvent`, so
     * the flag is already correct when the host decides how to deliver the
     * event.
     */
    private var swipeConsumedGesture = false

    /** A toast owned the gesture that started this stream; keep hands off it. */
    private var swipeYieldedToToast = false

    private val swipeSlop = ViewConfiguration.get(context).scaledPagingTouchSlop
    private val swipeMinVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity

    private val swipeDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                // Unambiguously horizontal, in both travel and speed, so a
                // vertical ScrollView drag never lands here.
                if (abs(dx) < swipeSlop || abs(dx) <= abs(dy)) return false
                if (abs(velocityX) < swipeMinVelocity || abs(velocityX) <= abs(velocityY)) {
                    return false
                }
                // Claimed from here on, including a swipe off the first or last
                // tab: the tab does not change, but the gesture was still a
                // swipe and must not fall through as a click.
                swipeConsumedGesture = true
                adjacentTab(currentTab, forward = dx < 0)?.let(::onTabClicked)
                return true
            }
        },
    )

    val selectedTab: Tab
        get() = currentTab

    init {
        binding.self = this
        TooltipCompat.setTooltipText(
            binding.refreshSubscription,
            context.getString(R.string.get_line_home_refresh_subscription),
        )
        TooltipCompat.setTooltipText(
            binding.shareSubscription,
            context.getString(R.string.get_line_share_subscription),
        )
        TooltipCompat.setTooltipText(
            binding.refreshServers,
            context.getString(R.string.get_line_home_refresh_servers),
        )
        binding.stateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.Retry)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddSubscription)
                GetLineRecoveryAction.OpenAccount -> request(Request.OpenAccount)
                GetLineRecoveryAction.SignIn -> request(Request.SignIn)
                GetLineRecoveryAction.ActivateTrial,
                GetLineRecoveryAction.OpenAccountPortal,
                GetLineRecoveryAction.None -> Unit
            }
        }
        binding.stateView.setOnSendDiagnostics {
            request(Request.SendDiagnostics)
        }
        binding.subscriptionStateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.RetrySubscription)
                GetLineRecoveryAction.SignIn -> request(Request.SignIn)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddSubscription)
                GetLineRecoveryAction.OpenAccount -> request(Request.OpenAccount)
                GetLineRecoveryAction.ActivateTrial,
                GetLineRecoveryAction.OpenAccountPortal,
                GetLineRecoveryAction.None -> Unit
            }
        }
        binding.serversStateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.RetryServers)
                else -> Unit
            }
        }
        binding.tabHome.setOnClickListener { onTabClicked(Tab.Home) }
        binding.tabSubscription.setOnClickListener { onTabClicked(Tab.Subscription) }
        applyStatus(VpnStatus.Disconnected)
        applyCard(content = null, isRefreshing = false, transientError = false)
        applyLocation(null)
        // Placeholder until the host reads the store: the same default the service
        // ships with, so the row never renders blank and never shows a wrong count.
        binding.appRoutingSummary = context.getString(R.string.get_line_home_app_routing_all)
        applyProductState(GetLineProductState.Loading, GetLineRecoveryAction.None)
        applySubscriptionScreen(SubscriptionScreen.Loading)
        applySubscriptionSignInBlock(visible = false)
        applyServersScreen(ServersScreen.Loading)
        applyServersRefreshControl(isRefreshing = false)
        applyAccountPortalUi(
            visible = false,
            launching = false,
            showError = false,
        )
        applyTab(Tab.Home)
    }

    /**
     * Switch visible destination. Does not touch VPN service — UI only.
     */
    fun setTab(tab: Tab) {
        applyTab(tab)
    }

    /**
     * Neighbour in shell-bar order. `null` at either end — the swipe stops
     * there instead of wrapping around, so the edges stay predictable.
     *
     * Servers is not in the bar: it is a detail of Home, reached from the
     * location card. A swipe neither lands on it nor leads out of it.
     */
    private fun adjacentTab(current: Tab, forward: Boolean): Tab? {
        val index = SHELL_TABS.indexOf(current)
        if (index < 0) return null
        return SHELL_TABS.getOrNull(index + if (forward) 1 else -1)
    }

    private fun onTabClicked(tab: Tab) {
        if (tab == currentTab) return
        applyTab(tab)
        request(
            when (tab) {
                Tab.Home -> Request.SelectHome
                Tab.Servers -> Request.SelectServers
                Tab.Subscription -> Request.SelectSubscription
            }
        )
    }

    /**
     * Feed a touch event seen by the host window. Returns `true` when this very
     * event completed a horizontal fling the screen took for itself — whether
     * or not the tab actually changed.
     *
     * The host must keep delivering events to the view tree as usual, but when
     * this returns `true` it has to hand the children `ACTION_CANCEL` instead
     * of the `ACTION_UP`: the finger normally stays inside the bounds of the
     * view it started on (a full-width server row, the connect button), so that
     * view would otherwise still fire its click on the back of the swipe.
     *
     * A gesture that starts on a visible toast is left alone entirely — that
     * one already means swipe-to-dismiss.
     */
    fun onHostTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeYieldedToToast = isOverVisibleToast(event)
        }
        if (swipeYieldedToToast) return false
        swipeConsumedGesture = false
        swipeDetector.onTouchEvent(event)
        return swipeConsumedGesture
    }

    /** Home location card — switch to Servers without touching VPN. */
    fun openServers() {
        onTabClicked(Tab.Servers)
    }

    /**
     * A pick the core confirmed sends the user back to Home: the list is only
     * ever reached from the location card, so picking a country was the whole
     * errand and before this they were left standing on the list (issue #44).
     *
     * Emits [Request.SelectHome] so the host persists the tab, exactly as if
     * Home had been tapped in the shell bar. Main thread only, like [setTab].
     */
    fun returnToHomeAfterServerSelection() {
        if (currentTab != Tab.Servers) return
        applyTab(Tab.Home)
        request(Request.SelectHome)
    }

    suspend fun setVpnStatus(status: VpnStatus) {
        withContext(Dispatchers.Main) {
            applyStatus(status)
        }
    }

    suspend fun setLocation(name: String?) {
        withContext(Dispatchers.Main) {
            applyLocation(name)
        }
    }

    /**
     * Home routing row summary.
     *
     * [selectedCount] is the stored selection, which may include packages that are
     * currently uninstalled or have no launcher icon — the same number the list
     * screen keeps. It is not recounted against what is installed, because the
     * selection itself is never trimmed.
     */
    suspend fun setAppRouting(mode: AppRoutingMode, selectedCount: Int) {
        withContext(Dispatchers.Main) {
            binding.appRoutingSummary = when (mode) {
                AppRoutingMode.All ->
                    context.getString(R.string.get_line_home_app_routing_all)
                AppRoutingMode.OnlySelected ->
                    context.resources.getQuantityString(
                        R.plurals.get_line_home_app_routing_only_selected,
                        selectedCount,
                        selectedCount,
                    )
                AppRoutingMode.ExceptSelected ->
                    context.resources.getQuantityString(
                        R.plurals.get_line_home_app_routing_except_selected,
                        selectedCount,
                        selectedCount,
                    )
            }
        }
    }

    suspend fun setSubscriptionScreen(screen: SubscriptionScreen) {
        withContext(Dispatchers.Main) {
            applySubscriptionScreen(screen)
        }
    }

    /**
     * Share control on the Subscription heading. Visibility only — the URL
     * never enters this design.
     */
    suspend fun setSubscriptionShareVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            binding.shareSubscription.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /** Account capability, independent of the subscription-card state. */
    suspend fun setSubscriptionSignInVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            applySubscriptionSignInBlock(visible)
        }
    }

    /**
     * Account portal CTA under the subscription card.
     * Home never shows this block.
     */
    suspend fun setAccountPortalUi(
        visible: Boolean,
        launching: Boolean = false,
        showError: Boolean = false,
    ) {
        withContext(Dispatchers.Main) {
            applyAccountPortalUi(visible, launching, showError)
        }
    }

    /** Distribution capability, independent of the subscription-card state. */
    suspend fun setAccountPortalAvailable(visible: Boolean) {
        withContext(Dispatchers.Main) {
            applyAccountPortalUi(
                visible = visible,
                launching = accountPortalLaunching && visible,
                showError = accountPortalErrorVisible && visible,
            )
        }
    }

    /**
     * Account action CTA at the bottom of the Subscription destination.
     * Sign out (session) or remove link-only subscription (managed binding only).
     */
    suspend fun setAccountAction(action: AccountAction) {
        withContext(Dispatchers.Main) {
            val button = binding.logoutAccount
            when (action) {
                AccountAction.None -> {
                    button.visibility = View.GONE
                }
                AccountAction.SignOut -> {
                    button.visibility = View.VISIBLE
                    button.setText(R.string.get_line_action_logout)
                    button.contentDescription =
                        context.getString(R.string.get_line_action_logout)
                }
                AccountAction.RemoveSubscription -> {
                    button.visibility = View.VISIBLE
                    button.setText(R.string.get_line_action_remove_subscription)
                    button.contentDescription =
                        context.getString(R.string.get_line_action_remove_subscription)
                }
            }
        }
    }

    /**
     * Confirm device sign-out or link-only profile removal.
     * One calm dialog — not a multi-warning security wall.
     * @return true if the user confirmed.
     */
    suspend fun confirmLogout(action: AccountAction): Boolean {
        if (action == AccountAction.None) return false
        val titleRes: Int
        val messageRes: Int
        val positiveRes: Int
        when (action) {
            AccountAction.SignOut -> {
                titleRes = R.string.get_line_logout_confirm_title
                messageRes = R.string.get_line_logout_confirm_message
                positiveRes = R.string.get_line_action_logout
            }
            AccountAction.RemoveSubscription -> {
                titleRes = R.string.get_line_remove_subscription_confirm_title
                messageRes = R.string.get_line_remove_subscription_confirm_message
                // Short form: the full label fits the card button, not a dialog button.
                positiveRes = R.string.get_line_action_remove_subscription_short
            }
            AccountAction.None -> return false
        }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(titleRes)
                    .setMessage(messageRes)
                    .setCancelable(true)
                    .setPositiveButton(positiveRes) { _, _ ->
                        if (!cont.isCompleted) cont.resume(true)
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .setOnDismissListener {
                        if (!cont.isCompleted) cont.resume(false)
                    }
                    .show()
                cont.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }

    suspend fun setServersScreen(screen: ServersScreen) {
        withContext(Dispatchers.Main) {
            applyServersScreen(screen)
        }
    }

    /** Icon/spinner crossfade on the Servers header while a manual refresh is in flight. */
    suspend fun setServersRefreshing(isRefreshing: Boolean) {
        withContext(Dispatchers.Main) {
            applyServersRefreshControl(isRefreshing)
        }
    }

    suspend fun setProductState(
        state: GetLineProductState,
        action: GetLineRecoveryAction = defaultAction(state),
    ) {
        withContext(Dispatchers.Main) {
            applyProductState(state, action)
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    fun requestSelectServer(name: String) {
        pendingServerName = name
        requests.trySend(Request.SelectServer)
    }

    fun consumePendingServerName(): String? {
        val name = pendingServerName
        pendingServerName = null
        return name
    }

    fun formatExpireUntil(epochMillis: Long): String {
        val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(epochMillis))
        return context.getString(R.string.get_line_home_expire_until_format, date)
    }

    fun formatDeviceLimit(limit: Int): String {
        return context.getString(R.string.get_line_home_devices_limit_format, limit)
    }

    fun formatApiTraffic(
        usedBytes: Long,
        limitBytes: Long,
        isUnlimited: Boolean,
    ): String {
        if (isUnlimited) {
            return context.getString(R.string.get_line_home_traffic_unlimited)
        }
        if (limitBytes > 0L) {
            return context.getString(
                R.string.get_line_home_traffic_format,
                usedBytes.toBytesString(),
                limitBytes.toBytesString(),
            )
        }
        if (usedBytes > 0L) {
            return context.getString(
                R.string.get_line_home_traffic_used_format,
                usedBytes.toBytesString(),
            )
        }
        return context.getString(R.string.get_line_home_traffic_unknown)
    }

    private fun applyTab(tab: Tab) {
        currentTab = tab
        binding.homeVisible = tab == Tab.Home
        binding.serversVisible = tab == Tab.Servers
        binding.subscriptionVisible = tab == Tab.Subscription
        // Servers is a child of Home, so the bar keeps Home lit while it is open
        // and tapping Home there is a way back.
        binding.tabHome.isSelected = tab == Tab.Home || tab == Tab.Servers
        binding.tabSubscription.isSelected = tab == Tab.Subscription
    }

    private fun applyStatus(status: VpnStatus) {
        vpnStatus = status
        binding.vpnStatus = status
        binding.statusText = when (status) {
            VpnStatus.Disconnected -> context.getString(R.string.get_line_home_status_disconnected)
            VpnStatus.Connecting -> context.getString(R.string.get_line_home_status_connecting)
            VpnStatus.Connected -> context.getString(R.string.get_line_home_status_connected)
        }
        binding.connectRingArc.setState(
            when (status) {
                VpnStatus.Disconnected -> GetLineConnectRingView.State.Disconnected
                VpnStatus.Connecting -> GetLineConnectRingView.State.Connecting
                VpnStatus.Connected -> GetLineConnectRingView.State.Connected
            }
        )
        applyRingDetail()
        applyRingDescription()
        applyControls()
    }

    /**
     * How long the tunnel has been up, and how much it carried.
     *
     * [sessionDurationMs] is measured by the service that owns the tunnel, so it
     * outlives this Activity; null means the tunnel is down.
     */
    suspend fun setSession(sessionDurationMs: Long?, traffic: GetLineTraffic) {
        withContext(Dispatchers.Main) {
            this@GetLineHomeDesign.sessionDurationMs = sessionDurationMs
            sessionTraffic = traffic
            applyRingDetail()
            applyRingDescription()
        }
    }

    private fun applyRingDetail() {
        binding.connectRingDetail.text = when (vpnStatus) {
            VpnStatus.Disconnected -> context.getString(R.string.get_line_home_connect_hint)
            VpnStatus.Connecting -> ""
            // Fall back to the hint rather than a fake 00:00 if the service has
            // not reported a start yet.
            VpnStatus.Connected -> sessionDurationMs?.let { formatDuration(it) }
                ?: context.getString(R.string.get_line_home_status_connected)
        }
        binding.connectRingTraffic.text = context.getString(
            R.string.get_line_home_session_traffic_format,
            sessionTraffic.formatTotal(context),
        )
        binding.connectRingTraffic.visibility =
            if (vpnStatus == VpnStatus.Connected) View.VISIBLE else View.GONE
    }

    /** h:mm:ss past an hour, m:ss below it — no leading zero hour all session. */
    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * The ring is a button, so it announces state and action only.
     *
     * Duration and traffic are deliberately left out: they change every second,
     * and every reassignment fires a content-change event, which would talk over
     * a TalkBack user parked on the control. Nothing is announced while a value
     * merely ticks — only when the state itself changes.
     */
    private fun applyRingDescription() {
        // Connecting disables the ring; offering "connect" would name an action
        // that cannot be taken and contradict the status being read out.
        val action = when (vpnStatus) {
            VpnStatus.Connecting -> null
            VpnStatus.Connected ->
                context.getString(R.string.get_line_home_ring_action_disconnect)
            VpnStatus.Disconnected ->
                context.getString(R.string.get_line_home_ring_action_connect)
        }
        val description = listOfNotNull(binding.statusText, action).joinToString(", ")
        if (binding.connectRing.contentDescription?.toString() != description) {
            binding.connectRing.contentDescription = description
        }
    }

    private fun applyLocation(name: String?) {
        val text = name?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.get_line_shell_location_unknown)
        binding.locationText = text
        applyControls()
    }

    private fun applyServersScreen(screen: ServersScreen) {
        when (screen) {
            is ServersScreen.Loading -> {
                binding.serversAvailableLabel.visibility = View.GONE
                binding.serversLatencyPrerequisite.visibility = View.GONE
                binding.serversList.visibility = View.GONE
                binding.serversList.removeAllViews()
                binding.serversStateView.renderMessage(
                    title = R.string.get_line_state_loading_title,
                    explanation = R.string.get_line_state_loading_explanation,
                    loading = true,
                    action = GetLineRecoveryAction.None,
                )
            }
            is ServersScreen.Ready -> {
                binding.serversStateView.hide()
                binding.serversAvailableLabel.visibility = View.VISIBLE
                binding.serversLatencyPrerequisite.visibility = if (
                    screen.showLatencyPrerequisite
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                binding.serversList.visibility = View.VISIBLE
                bindServerGroups(screen.groups, screen.selectable)
            }
            is ServersScreen.Empty -> {
                binding.serversAvailableLabel.visibility = View.GONE
                binding.serversLatencyPrerequisite.visibility = View.GONE
                binding.serversList.visibility = View.GONE
                binding.serversList.removeAllViews()
                binding.serversStateView.renderMessage(
                    title = R.string.get_line_servers_empty_title,
                    explanation = R.string.get_line_servers_empty_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
            is ServersScreen.Failed -> {
                binding.serversAvailableLabel.visibility = View.GONE
                binding.serversLatencyPrerequisite.visibility = View.GONE
                binding.serversList.visibility = View.GONE
                binding.serversList.removeAllViews()
                binding.serversStateView.renderMessage(
                    title = R.string.get_line_servers_load_failed_title,
                    explanation = R.string.get_line_servers_load_failed_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
        }
    }

    private fun bindServerGroups(groups: List<ServerGroupRow>, selectable: Boolean) {
        lastServerGroups = groups
        lastServersSelectable = selectable
        val list = binding.serversList
        list.removeAllViews()
        // Drop expansion state for countries that no longer exist after a refresh.
        expandedGroupKeys.retainAll(groups.mapTo(mutableSetOf()) { it.key })

        val inflater = layoutInflater
        val selectedStroke = ContextCompat.getColor(context, R.color.getline_brand_primary)
        val idleStroke = ContextCompat.getColor(context, R.color.getline_hairline)

        for (group in groups) {
            group.sectionLabel?.let { heading ->
                val view = inflater.inflate(R.layout.item_get_line_server_section, list, false)
                (view as TextView).text = heading
                // TalkBack can then jump between sections instead of walking every card.
                ViewCompat.setAccessibilityHeading(view, true)
                list.addMatchWidth(view)
            }

            val expanded = group.expandable && expandedGroupKeys.contains(group.key)
            val card = inflater.inflate(R.layout.item_get_line_server_group, list, false)

            card.findViewById<TextView>(R.id.group_label).text = group.label
            bindOptionalText(
                card.findViewById(R.id.group_variant),
                groupSubtitle(group),
            )
            bindLatencySlot(
                delayView = card.findViewById(R.id.group_delay),
                progressView = card.findViewById(R.id.group_delay_progress),
                delayMs = group.delayMs,
                showProgress = group.showLatencyProgress,
            )
            (card as? MaterialCardView)?.strokeColor =
                if (group.selected) selectedStroke else idleStroke

            val selectArea = card.findViewById<View>(R.id.group_select)
            selectArea.isEnabled = selectable
            card.alpha = if (selectable) 1f else 0.6f
            // Selection was carried by the cyan stroke alone; state must also be
            // semantic so TalkBack announces it. The row reads as one node, not
            // as three loose labels.
            selectArea.isSelected = group.selected
            selectArea.contentDescription = rowDescription(
                label = group.label,
                detail = groupSubtitle(group),
                delayMs = group.delayMs,
                activeViaGroup = false,
                latencyProbing = group.showLatencyProgress,
            )
            if (selectable) {
                selectArea.setOnClickListener {
                    // Host distinguishes a core-confirmed choice from an optimistic
                    // in-flight one before deciding whether to return Home.
                    requestSelectServer(group.primaryName)
                }
            } else {
                selectArea.setOnClickListener(null)
            }

            val expandArea = card.findViewById<View>(R.id.group_expand)
            val divider = card.findViewById<View>(R.id.group_divider)
            if (group.expandable) {
                expandArea.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
                card.findViewById<View>(R.id.group_chevron).rotation =
                    if (expanded) 180f else 0f
                expandArea.contentDescription = context.getString(
                    if (expanded) {
                        R.string.get_line_server_collapse
                    } else {
                        R.string.get_line_server_expand
                    },
                )
                expandArea.setOnClickListener {
                    if (!expandedGroupKeys.add(group.key)) {
                        expandedGroupKeys.remove(group.key)
                    }
                    // Re-render from the latest state, not from the list this
                    // listener closed over: a refresh in between would otherwise
                    // be undone by the next expand tap.
                    bindServerGroups(lastServerGroups, lastServersSelectable)
                }
            } else {
                expandArea.visibility = View.GONE
                divider.visibility = View.GONE
                expandArea.setOnClickListener(null)
            }

            list.addMatchWidth(card)

            if (expanded) {
                for (variant in group.variants) {
                    list.addMatchWidth(
                        inflateVariantRow(inflater, list, variant, selectable),
                    )
                }
            }
        }
    }

    private fun inflateVariantRow(
        inflater: android.view.LayoutInflater,
        parent: LinearLayout,
        variant: ServerRow,
        selectable: Boolean,
    ): View {
        val row = inflater.inflate(R.layout.item_get_line_server_variant, parent, false)

        val marked = variant.selected || variant.activeViaGroup
        row.findViewById<TextView>(R.id.variant_label).apply {
            text = variantSubtitle(variant)
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (marked) R.color.getline_text_primary
                    else R.color.getline_text_secondary,
                )
            )
        }
        // Keep the check slot occupied so labels stay aligned across rows.
        // A cyan check is the user's own pick; a muted one means the node is
        // running because a nested group chose it.
        row.findViewById<ImageView>(R.id.variant_check).apply {
            visibility = if (marked) View.VISIBLE else View.INVISIBLE
            imageTintList = ContextCompat.getColorStateList(
                context,
                if (variant.selected) R.color.getline_accent
                else R.color.getline_text_secondary,
            )
        }
        bindLatencySlot(
            delayView = row.findViewById(R.id.variant_delay),
            progressView = row.findViewById(R.id.variant_delay_progress),
            delayMs = variant.delayMs,
            showProgress = variant.showLatencyProgress,
        )

        row.isEnabled = selectable
        // The check icon is decorative; state travels semantically instead.
        row.isSelected = variant.selected
        row.contentDescription = rowDescription(
            label = variantSubtitle(variant),
            detail = null,
            delayMs = variant.delayMs,
            activeViaGroup = variant.activeViaGroup,
            latencyProbing = variant.showLatencyProgress,
        )
        if (selectable) {
            row.setOnClickListener {
                requestSelectServer(variant.name)
            }
        } else {
            row.setOnClickListener(null)
        }
        return row
    }

    /**
     * One spoken sentence per row.
     *
     * Selected state is left to [View.isSelected] so it is not announced twice;
     * "running via a nested group" has no such flag and is spelled out.
     */
    private fun rowDescription(
        label: String,
        detail: String?,
        delayMs: Int?,
        activeViaGroup: Boolean,
        latencyProbing: Boolean,
    ): CharSequence {
        val parts = mutableListOf(label)
        detail?.takeIf { it.isNotBlank() && it != label }?.let(parts::add)
        parts += if (latencyProbing) {
            context.getString(R.string.get_line_server_latency_checking)
        } else if (delayMs != null) {
            context.getString(R.string.get_line_server_delay_format, delayMs)
        } else {
            context.getString(R.string.get_line_server_delay_unknown_talkback)
        }
        if (activeViaGroup) {
            parts += context.getString(R.string.get_line_server_active_via_group)
        }
        return parts.joinToString(", ")
    }

    /**
     * Subtitle under a country: what the row would activate, plus the protocol
     * the core reports. A nested group instead names the node it routes through.
     */
    private fun groupSubtitle(group: ServerGroupRow): String? {
        group.resolvedLabel?.let {
            return context.getString(R.string.get_line_server_now_format, it)
        }
        // A group with one variant has no expanded list to carry the protocol,
        // and the row is that node rather than a summary of several — so show it.
        if (!group.expandable) {
            return joinDetails(group.variantLabel, group.protocol)
        }
        // Otherwise leave it to the expanded list. On a relay node the core reports
        // the first hop ("Обход vless" -> Trojan), so next to the name it reads as
        // a second opinion about one thing rather than two legs of a route.
        return group.variantLabel?.takeIf { it.isNotBlank() }
    }

    private fun variantSubtitle(variant: ServerRow): String {
        return joinDetails(variant.displayName, variant.protocol) ?: variant.displayName
    }

    private fun joinDetails(label: String?, protocol: String?): String? {
        val name = label?.takeIf { it.isNotBlank() }
        // Do not restate what the name already says ("vless · Vless").
        val extra = protocol
            ?.takeIf { it.isNotBlank() }
            ?.takeUnless { name != null && name.contains(it, ignoreCase = true) }
        val parts = listOfNotNull(name, extra)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(DETAIL_SEPARATOR)
    }

    /** Unmeasured nodes show a dash: absent is not the same as fast. */
    private fun delayText(delayMs: Int?): String {
        return delayMs?.let { context.getString(R.string.get_line_server_delay_format, it) }
            ?: context.getString(R.string.get_line_server_delay_unknown)
    }

    private fun bindLatencySlot(
        delayView: TextView,
        progressView: ProgressBar,
        delayMs: Int?,
        showProgress: Boolean,
    ) {
        delayView.text = delayText(delayMs)
        progressView.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (showProgress) {
            crossFade(show = progressView, hide = delayView)
        } else {
            crossFade(show = delayView, hide = progressView)
        }
    }

    private fun bindOptionalText(view: TextView, value: String?) {
        view.text = value.orEmpty()
        view.visibility = if (value == null) View.GONE else View.VISIBLE
    }

    private fun LinearLayout.addMatchWidth(child: View) {
        addView(
            child,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun applySubscriptionScreen(screen: SubscriptionScreen) {
        when (screen) {
            is SubscriptionScreen.Loading -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_state_loading_title,
                    explanation = R.string.get_line_state_loading_explanation,
                    loading = true,
                    action = GetLineRecoveryAction.None,
                )
            }
            is SubscriptionScreen.Ready -> {
                binding.subscriptionStateView.hide()
                applyCard(
                    content = screen.card,
                    isRefreshing = screen.isRefreshing,
                    transientError = screen.transientError,
                )
            }
            is SubscriptionScreen.Empty -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_subscription_empty_title,
                    explanation = R.string.get_line_subscription_empty_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
            is SubscriptionScreen.Failed -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_subscription_load_failed_title,
                    explanation = R.string.get_line_subscription_load_failed_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
        }
    }

    private fun applySubscriptionSignInBlock(visible: Boolean) {
        binding.subscriptionSignInBlock.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyAccountPortalUi(
        visible: Boolean,
        launching: Boolean,
        showError: Boolean,
    ) {
        accountPortalVisible = visible
        accountPortalLaunching = launching
        accountPortalErrorVisible = showError && visible && !launching

        binding.accountPortalBlock.visibility =
            if (visible) View.VISIBLE else View.GONE

        val button = binding.openAccountPortal
        if (visible) {
            button.isEnabled = !launching
            button.alpha = if (launching) 0.6f else 1f
            button.text = context.getString(
                if (launching) {
                    R.string.get_line_account_portal_opening
                } else {
                    R.string.get_line_account_portal_open
                },
            )
            button.contentDescription = button.text
        }

        binding.accountPortalError.visibility =
            if (accountPortalErrorVisible) View.VISIBLE else View.GONE
        binding.accountPortalRetry.isEnabled = visible && !launching
    }

    private fun applyCard(
        content: CardContent?,
        isRefreshing: Boolean,
        transientError: Boolean,
        transientErrorTextRes: Int = R.string.get_line_subscription_transient_error,
    ) {
        hasProfile = content != null
        if (content == null) {
            binding.profileCardVisible = false
            binding.cardTitle = context.getString(R.string.get_line_home_no_profile)
            binding.cardTitleVisible = false
            binding.subscriptionStatusText = ""
            binding.subscriptionStatusVisible = false
            binding.expireText = context.getString(R.string.get_line_home_expire_unknown)
            binding.daysLeftValue = ""
            binding.daysLeftUnit = ""
            binding.daysLeftVisible = false
            binding.trafficText = context.getString(R.string.get_line_home_traffic_unknown)
            binding.trafficProgress.visibility = View.GONE
            binding.devicesText = ""
            binding.devicesVisible = false
            binding.subscriptionTransientError.setText(R.string.get_line_subscription_transient_error)
            binding.subscriptionTransientError.visibility = View.GONE
            binding.refreshSubscription.visibility = View.GONE
            binding.refreshSubscriptionProgress.visibility = View.GONE
        } else {
            binding.profileCardVisible = true
            binding.cardTitle = content.title.orEmpty()
            binding.cardTitleVisible = content.title != null
            binding.subscriptionStatusText = content.statusText.orEmpty()
            binding.subscriptionStatusVisible = content.statusText != null
            binding.expireText = content.expireText
            binding.trafficText = content.trafficText
            binding.devicesText = content.devicesText.orEmpty()
            binding.devicesVisible = content.devicesText != null
            binding.subscriptionTransientError.setText(transientErrorTextRes)
            binding.subscriptionTransientError.visibility =
                if (transientError) View.VISIBLE else View.GONE

            applyStatusPill(content.isActive, content.statusText)
            applyDaysLeft(content.daysLeft)
            applyTrafficProgress(content.trafficUsedFraction)
            applyRefreshControl(isRefreshing)
        }
        applyControls()
    }

    private fun applyStatusPill(isActive: Boolean, statusText: String?) {
        if (statusText == null) return

        val pill = binding.subscriptionStatusPill
        val fill = if (isActive) R.color.getline_pill_positive else R.color.getline_pill_negative
        val ink = if (isActive) R.color.getline_brand_primary else R.color.getline_error

        pill.backgroundTintList = ContextCompat.getColorStateList(context, fill)
        pill.setTextColor(ContextCompat.getColor(context, ink))
    }

    private fun applyDaysLeft(daysLeft: Int?) {
        binding.daysLeftVisible = daysLeft != null
        if (daysLeft == null) {
            binding.daysLeftValue = ""
            binding.daysLeftUnit = ""
            binding.daysLeftBlock.contentDescription = null
            return
        }

        val unit = context.resources.getQuantityString(
            R.plurals.get_line_home_days_left_unit,
            daysLeft,
        )
        binding.daysLeftValue = daysLeft.toString()
        binding.daysLeftUnit = unit
        // Two views only so the number and its noun share a baseline; announce
        // them as the one phrase a sighted user reads.
        binding.daysLeftBlock.contentDescription = "$daysLeft $unit"
    }

    private fun applyTrafficProgress(usedFraction: Float?) {
        val bar = binding.trafficProgress
        if (usedFraction == null) {
            bar.visibility = View.GONE
            return
        }

        bar.visibility = View.VISIBLE
        // Scaled to the view's max rather than a percentage: at 1000 steps a
        // few megabytes off a 15 GiB plan still move the bar.
        bar.progress = (usedFraction.coerceIn(0f, 1f) * bar.max).roundToInt()
    }

    /**
     * The icon and the spinner sit in the same 48dp slot, so swapping visibility
     * outright cut one to the other in a single frame. Fade instead.
     */
    private fun applyRefreshControl(isRefreshing: Boolean) {
        val icon = binding.refreshSubscription
        val spinner = binding.refreshSubscriptionProgress

        icon.isEnabled = !isRefreshing
        icon.contentDescription = context.getString(
            if (isRefreshing) {
                R.string.get_line_subscription_refreshing
            } else {
                R.string.get_line_home_refresh_subscription
            }
        )

        if (isRefreshing) {
            crossFade(show = spinner, hide = icon)
        } else {
            crossFade(show = icon, hide = spinner)
        }
    }

    /** Same icon/spinner crossfade as [applyRefreshControl], for the Servers header. */
    private fun applyServersRefreshControl(isRefreshing: Boolean) {
        val icon = binding.refreshServers
        val spinner = binding.refreshServersProgress

        icon.isEnabled = !isRefreshing
        icon.contentDescription = context.getString(
            if (isRefreshing) {
                R.string.get_line_servers_refreshing
            } else {
                R.string.get_line_home_refresh_servers
            }
        )

        if (isRefreshing) {
            crossFade(show = spinner, hide = icon)
        } else {
            crossFade(show = icon, hide = spinner)
        }
    }

    private fun crossFade(show: View, hide: View) {
        if (show.visibility == View.VISIBLE && show.alpha == 1f) {
            // Already settled — every render would otherwise restart the fade.
            return
        }

        show.animate().cancel()
        hide.animate().cancel()

        show.alpha = 0f
        show.visibility = View.VISIBLE
        show.animate().alpha(1f).setDuration(REFRESH_FADE_MS).start()

        hide.animate()
            .alpha(0f)
            .setDuration(REFRESH_FADE_MS)
            // INVISIBLE, not GONE: both stay stacked in the same slot, and an
            // invisible view cannot be tapped.
            .withEndAction { hide.visibility = View.INVISIBLE }
            .start()
    }

    private fun applyProductState(
        state: GetLineProductState,
        action: GetLineRecoveryAction,
    ) {
        productState = state
        binding.stateView.render(
            state = state,
            action = action,
            showSendDiagnostics = state == GetLineProductState.ImportFailed ||
                state == GetLineProductState.AuthFailed,
        )
        applyControls()
    }

    private fun applyControls() {
        // Home VPN controls only — subscription card visibility is independent.
        val controlsVisible = hasKnownHomeControls() ||
            (vpnStatus == VpnStatus.Connected &&
                (productState == GetLineProductState.Loading ||
                    productState == GetLineProductState.BackendUnavailable))
        val primaryAllowed = when (productState) {
            GetLineProductState.Loading,
            GetLineProductState.PreparingVpn,
            GetLineProductState.SubscriptionExpired -> false
            GetLineProductState.BackendUnavailable ->
                hasKnownHomeControls() || vpnStatus == VpnStatus.Connected
            else -> true
        }

        binding.vpnControlsVisible = controlsVisible
        binding.primaryActionEnabled =
            controlsVisible && primaryAllowed && vpnStatus != VpnStatus.Connecting
        // Location row is navigation into Servers, not a VPN control: it does not
        // follow controlsVisible. Opens the Servers tab only — does not touch VPN
        // or open legacy Proxy UI.
        binding.locationVisible = homeHasImportedProfile
    }

    /**
     * Home VPN availability is tracked via product-state path, not the subscription card.
     * [hasProfile] is subscription-card visibility; Home uses product snapshot flags instead.
     */
    private var homeHasActiveProfile: Boolean = false

    /**
     * Any imported profile exists — the only condition for showing the way into
     * Servers. The list itself decides what it can show (live core or the offline
     * catalog); Home does not predict that.
     *
     * Starts true on purpose: an entry point that vanishes leaves the user with no
     * way back to it, while an entry point that opens an empty list is recoverable.
     */
    private var homeHasImportedProfile: Boolean = true

    fun setHomeHasActiveProfile(value: Boolean) {
        homeHasActiveProfile = value
        applyControls()
    }

    fun setHomeHasImportedProfile(value: Boolean) {
        homeHasImportedProfile = value
        applyControls()
    }

    private fun hasKnownHomeControls(): Boolean = homeHasActiveProfile

    private companion object {
        const val DETAIL_SEPARATOR = " · "
        const val REFRESH_FADE_MS = 150L

        /** Shell-bar destinations, in bar order. Servers is not one of them. */
        val SHELL_TABS = listOf(Tab.Home, Tab.Subscription)
    }

    private fun defaultAction(state: GetLineProductState): GetLineRecoveryAction {
        return when (state) {
            GetLineProductState.Offline,
            GetLineProductState.BackendUnavailable,
            GetLineProductState.ImportFailed,
            GetLineProductState.AuthFailed -> GetLineRecoveryAction.Retry
            // Email-only onboarding states; Home should not surface them as primary CTAs.
            GetLineProductState.AuthEmailEntry,
            GetLineProductState.AuthEmailOtpSent,
            GetLineProductState.AuthInvalidOtp,
            GetLineProductState.AuthOtpExpired,
            GetLineProductState.AuthEmailDomainNotAllowed,
            GetLineProductState.AuthNoAccount,
            GetLineProductState.AuthRateLimited,
            GetLineProductState.SessionStorageRecovered,
            GetLineProductState.SessionStorageUnavailable,
            GetLineProductState.NoSubscription,
            GetLineProductState.TrialUnavailable -> GetLineRecoveryAction.None
            GetLineProductState.NoProfile -> GetLineRecoveryAction.ImportSubscription
            GetLineProductState.ConnectionRepairFailed,
            GetLineProductState.ConnectionRestoreFailed -> GetLineRecoveryAction.Retry
            GetLineProductState.SubscriptionExpired -> GetLineRecoveryAction.OpenAccount
            GetLineProductState.Content,
            GetLineProductState.Loading,
            GetLineProductState.PreparingVpn -> GetLineRecoveryAction.None
        }
    }
}
