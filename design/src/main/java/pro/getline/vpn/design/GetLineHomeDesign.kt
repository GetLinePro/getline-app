package pro.getline.vpn.design

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import pro.getline.vpn.core.model.Traffic
import pro.getline.vpn.core.util.trafficTotal
import pro.getline.vpn.design.databinding.DesignGetLineHomeBinding
import pro.getline.vpn.design.view.GetLineConnectRingView
import pro.getline.vpn.design.model.GetLineProductState
import pro.getline.vpn.design.model.GetLineRecoveryAction
import pro.getline.vpn.design.util.layoutInflater
import pro.getline.vpn.design.util.root
import pro.getline.vpn.design.util.toBytesString
import pro.getline.vpn.design.util.toDateStr
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class GetLineHomeDesign(context: Context) : Design<GetLineHomeDesign.Request>(context) {
    enum class Request {
        ToggleVpn,
        SelectHome,
        SelectServers,
        SelectSubscription,
        Retry,
        AddSubscription,
        OpenProfiles,
        OpenAccount,
        /**
         * Open GetLine web account portal (Custom Tab) from Subscription CTA.
         * Not native checkout; does not create native session.
         */
        OpenAccountPortal,
        RefreshSubscription,
        /** Retry Subscription destination load (not Home product-state retry). */
        RetrySubscription,
        /** Open existing onboarding/auth from Subscription signed-out. */
        SignIn,
        /** Retry Servers destination load. */
        RetryServers,
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
    }

    /** Payload for [Request.SelectServer]; cleared when the request is consumed. */
    var pendingServerName: String? = null
        private set

    enum class VpnStatus {
        Disconnected,
        Connecting,
        Connected,
    }

    enum class Tab {
        Home,
        Servers,
        Subscription,
    }

    /**
     * Subscription card content (Subscription destination only).
     * Built from /api/subscriptions preferred item presentation.
     */
    data class CardContent(
        val title: String,
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

        data class SignedOut(
            val hasImportedProfile: Boolean,
        ) : SubscriptionScreen

        data object Failed : SubscriptionScreen
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
        ) : ServersScreen

        data object Empty : ServersScreen

        data object VpnStopped : ServersScreen

        data object Failed : ServersScreen
    }

    /**
     * A country row. Tapping it selects [primaryName]; the chevron expands
     * [variants]. A group with a single variant has nothing to expand.
     */
    data class ServerGroupRow(
        val key: String,
        val label: String,
        val variantLabel: String?,
        val protocol: String?,
        val delayMs: Int?,
        val primaryName: String,
        val selected: Boolean,
        /** Leaf a nested group ("⚡ Авто") currently routes through. */
        val resolvedLabel: String?,
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
    )

    private val binding = DesignGetLineHomeBinding
        .inflate(context.layoutInflater, context.root, false)

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

    /** Raw name Mihomo reports as active — used to skip no-op reselection. */
    private var currentSelectedServerName: String? = null

    /**
     * Packed Traffic from the core: upload in the high 32 bits, download in the
     * low ones, each scale-encoded. Only trafficTotal() may read it — treating
     * the raw Long as a byte count renders nonsense like "4.00 EiB".
     */
    private var trafficTotalBits: Traffic = 0L
    private var sessionDurationMs: Long? = null

    /** Latest rendered server list, so expanding cannot resurrect a stale one. */
    private var lastServerGroups: List<ServerGroupRow> = emptyList()
    private var lastServersSelectable: Boolean = false

    val selectedTab: Tab
        get() = currentTab

    init {
        binding.self = this
        TooltipCompat.setTooltipText(
            binding.refreshSubscription,
            context.getString(R.string.get_line_home_refresh_subscription),
        )
        binding.stateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.Retry)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddSubscription)
                GetLineRecoveryAction.OpenProfiles -> request(Request.OpenProfiles)
                GetLineRecoveryAction.OpenAccount -> request(Request.OpenAccount)
                GetLineRecoveryAction.SignIn -> request(Request.SignIn)
                GetLineRecoveryAction.None -> Unit
            }
        }
        binding.subscriptionStateView.setOnRecoveryAction {
            when (it) {
                GetLineRecoveryAction.Retry -> request(Request.RetrySubscription)
                GetLineRecoveryAction.SignIn -> request(Request.SignIn)
                GetLineRecoveryAction.ImportSubscription ->
                    request(Request.AddSubscription)
                GetLineRecoveryAction.OpenProfiles -> request(Request.OpenProfiles)
                GetLineRecoveryAction.OpenAccount -> request(Request.OpenAccount)
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
        binding.tabServers.setOnClickListener { onTabClicked(Tab.Servers) }
        binding.tabSubscription.setOnClickListener { onTabClicked(Tab.Subscription) }
        applyStatus(VpnStatus.Disconnected)
        applyCard(content = null, isRefreshing = false, transientError = false)
        applyLocation(null)
        applyProductState(GetLineProductState.Loading, GetLineRecoveryAction.None)
        applySubscriptionScreen(SubscriptionScreen.Loading)
        applyServersScreen(ServersScreen.Loading)
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

    /** Home location row — switch to Servers without touching VPN. */
    fun openServers() {
        onTabClicked(Tab.Servers)
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

    suspend fun setSubscriptionScreen(screen: SubscriptionScreen) {
        withContext(Dispatchers.Main) {
            applySubscriptionScreen(screen)
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

    /**
     * Logout CTA at the bottom of the Subscription destination.
     * Shown when a native session or managed GetLine binding is present.
     */
    suspend fun setLogoutVisible(visible: Boolean) {
        withContext(Dispatchers.Main) {
            binding.logoutAccount.visibility =
                if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Confirm device sign-out. One calm dialog — not a multi-warning security wall.
     * @return true if the user confirmed.
     */
    suspend fun confirmLogout(): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.get_line_logout_confirm_title)
                    .setMessage(R.string.get_line_logout_confirm_message)
                    .setCancelable(true)
                    .setPositiveButton(R.string.get_line_action_logout) { _, _ ->
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
        binding.tabHome.isSelected = tab == Tab.Home
        binding.tabServers.isSelected = tab == Tab.Servers
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
    suspend fun setSession(sessionDurationMs: Long?, traffic: Traffic) {
        withContext(Dispatchers.Main) {
            this@GetLineHomeDesign.sessionDurationMs = sessionDurationMs
            trafficTotalBits = traffic
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
            R.string.get_line_home_forwarded_format,
            trafficTotalBits.trafficTotal(),
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
                binding.serversList.visibility = View.VISIBLE
                currentSelectedServerName = screen.groups
                    .flatMap { it.variants }
                    .firstOrNull { it.selected }
                    ?.name
                bindServerGroups(screen.groups, screen.selectable)
            }
            is ServersScreen.Empty -> {
                binding.serversAvailableLabel.visibility = View.GONE
                binding.serversList.visibility = View.GONE
                binding.serversList.removeAllViews()
                binding.serversStateView.renderMessage(
                    title = R.string.get_line_servers_empty_title,
                    explanation = R.string.get_line_servers_empty_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
            is ServersScreen.VpnStopped -> {
                binding.serversAvailableLabel.visibility = View.GONE
                binding.serversList.visibility = View.GONE
                binding.serversList.removeAllViews()
                binding.serversStateView.renderMessage(
                    title = R.string.get_line_servers_vpn_stopped_title,
                    explanation = R.string.get_line_servers_vpn_stopped_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.None,
                )
            }
            is ServersScreen.Failed -> {
                binding.serversAvailableLabel.visibility = View.GONE
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

        val inflater = context.layoutInflater
        val selectedStroke = ContextCompat.getColor(context, R.color.getline_brand_primary)
        val idleStroke = ContextCompat.getColor(context, R.color.getline_hairline)

        for (group in groups) {
            val expanded = group.expandable && expandedGroupKeys.contains(group.key)
            val card = inflater.inflate(R.layout.item_get_line_server_group, list, false)

            card.findViewById<TextView>(R.id.group_label).text = group.label
            bindOptionalText(
                card.findViewById(R.id.group_variant),
                groupSubtitle(group),
            )
            bindOptionalText(
                card.findViewById(R.id.group_delay),
                delayText(group.delayMs),
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
            )
            if (selectable) {
                selectArea.setOnClickListener {
                    // Tapping the active country is a no-op: primaryName is already it.
                    if (group.primaryName != currentSelectedServerName) {
                        requestSelectServer(group.primaryName)
                    }
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
        bindOptionalText(
            row.findViewById(R.id.variant_delay),
            delayText(variant.delayMs),
        )

        row.isEnabled = selectable
        // The check icon is decorative; state travels semantically instead.
        row.isSelected = variant.selected
        row.contentDescription = rowDescription(
            label = variantSubtitle(variant),
            detail = null,
            delayMs = variant.delayMs,
            activeViaGroup = variant.activeViaGroup,
        )
        if (selectable) {
            row.setOnClickListener {
                if (!variant.selected) {
                    requestSelectServer(variant.name)
                }
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
    ): CharSequence {
        val parts = mutableListOf(label)
        detail?.takeIf { it.isNotBlank() && it != label }?.let(parts::add)
        parts += if (delayMs != null) {
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
                // Hide portal CTA while loading (native session unknown / in flight).
                applyAccountPortalUi(visible = false, launching = false, showError = false)
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
                applyAccountPortalUi(
                    visible = true,
                    launching = accountPortalLaunching,
                    showError = accountPortalErrorVisible,
                )
            }
            is SubscriptionScreen.Empty -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                // Authenticated empty — user can resolve plan issues in the web cabinet.
                applyAccountPortalUi(
                    visible = true,
                    launching = accountPortalLaunching,
                    showError = accountPortalErrorVisible,
                )
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_subscription_empty_title,
                    explanation = R.string.get_line_subscription_empty_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
            is SubscriptionScreen.SignedOut -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                // Browser login does not create native session — keep Sign in only.
                applyAccountPortalUi(visible = false, launching = false, showError = false)
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_subscription_signed_out_title,
                    explanation = R.string.get_line_subscription_signed_out_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.SignIn,
                )
            }
            is SubscriptionScreen.Failed -> {
                applyCard(content = null, isRefreshing = false, transientError = false)
                // Failed after a session-backed load attempt — portal may still help.
                applyAccountPortalUi(
                    visible = true,
                    launching = accountPortalLaunching,
                    showError = accountPortalErrorVisible,
                )
                binding.subscriptionStateView.renderMessage(
                    title = R.string.get_line_subscription_load_failed_title,
                    explanation = R.string.get_line_subscription_load_failed_explanation,
                    loading = false,
                    action = GetLineRecoveryAction.Retry,
                )
            }
        }
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
    ) {
        hasProfile = content != null
        if (content == null) {
            binding.profileCardVisible = false
            binding.cardTitle = context.getString(R.string.get_line_home_no_profile)
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
            binding.subscriptionTransientError.visibility = View.GONE
            binding.refreshSubscription.visibility = View.GONE
            binding.refreshSubscriptionProgress.visibility = View.GONE
        } else {
            binding.profileCardVisible = true
            binding.cardTitle = content.title
            binding.subscriptionStatusText = content.statusText.orEmpty()
            binding.subscriptionStatusVisible = content.statusText != null
            binding.expireText = content.expireText
            binding.trafficText = content.trafficText
            binding.devicesText = content.devicesText.orEmpty()
            binding.devicesVisible = content.devicesText != null
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
        binding.stateView.render(state, action)
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
        // Location row on Home when profile/controls are available.
        // Opens Servers tab only — does not touch VPN or open legacy Proxy UI.
        binding.locationVisible = controlsVisible
    }

    /**
     * Home VPN availability is tracked via product-state path, not the subscription card.
     * [hasProfile] is subscription-card visibility; Home uses product snapshot flags instead.
     */
    private var homeHasActiveProfile: Boolean = false

    fun setHomeHasActiveProfile(value: Boolean) {
        homeHasActiveProfile = value
        applyControls()
    }

    private fun hasKnownHomeControls(): Boolean = homeHasActiveProfile

    private companion object {
        const val DETAIL_SEPARATOR = " · "
        const val REFRESH_FADE_MS = 150L
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
            GetLineProductState.AuthRateLimited -> GetLineRecoveryAction.None
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
