package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.preference.OnChangedListener
import com.github.kr328.clash.design.preference.TipsPreference
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.selectableList
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.AccessControlMode
import com.github.kr328.clash.service.store.ServiceStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AccessControlDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    initialMode: AccessControlMode,
    private val selected: MutableSet<String>,
) : Design<AccessControlDesign.Request>(context) {
    enum class Request {
        ReloadApps,
        SelectAll,
        SelectNone,
        SelectInvert,
        Import,
        Export,
    }

    private val binding = DesignAccessControlBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppAdapter(context, selected)

    /**
     * Mirrors [AppAdapter.selectable] for surfaces built later than the mode row:
     * the search dialog and the overflow menu, both of which mutate the same
     * selection and must not stay live while the list is inert.
     */
    private var listSelectable: Boolean = true

    private val menu: AccessControlMenu by lazy {
        AccessControlMenu(context, binding.menuView, uiStore, requests)
    }

    val apps: List<AppInfo>
        get() = adapter.apps

    override val root: View
        get() = binding.root

    suspend fun patchApps(apps: List<AppInfo>) {
        adapter.swapDataSet(adapter::apps, apps, false)
    }

    suspend fun rebindAll() {
        withContext(Dispatchers.Main) {
            adapter.rebindAll()
        }
    }

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }

        bindModeHeader(srvStore, initialMode)

        // The bar carries the mode row and, in the two selective modes, a warning
        // that wraps to a different number of lines per locale. Measure it instead
        // of guessing: a constant here would either hide the first app under the
        // bar or leave a gap. Posted, so padding is never set mid-layout.
        binding.activityBarLayout.addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top
            if (binding.mainList.paddingTop != height) {
                view.post {
                    binding.mainList.updatePadding(top = height)
                }
            }
        }

        binding.menuView.setOnClickListener {
            menu.show(selectable = listSelectable)
        }

        binding.searchView.setOnClickListener {
            launch {
                try {
                    requestSearch()
                } finally {
                    withContext(NonCancellable) {
                        rebindAll()
                    }
                }
            }
        }
    }

    /**
     * Routing mode above the list.
     *
     * Writes straight through to [ServiceStore]; the tunnel is not re-established
     * here. AccessControlActivity does that once on the way out, so a user still
     * making up their mind does not drop the connection on every tap.
     */
    private fun bindModeHeader(srvStore: ServiceStore, initialMode: AccessControlMode) {
        val modes = AccessControlMode.values()

        val header = preferenceScreen(context) {
            // Set after the row below is built — both lines belong under the mode
            // they qualify, and the screen renders elements in call order.
            var lockdown: TipsPreference? = null
            var inert: TipsPreference? = null

            selectableList(
                value = srvStore::accessControlMode,
                values = modes,
                valuesText = arrayOf(
                    R.string.allow_all_apps,
                    R.string.allow_selected_apps,
                    R.string.deny_selected_apps,
                ),
                title = R.string.access_control_mode,
            ) {
                listener = OnChangedListener {
                    applyMode(modes[selected], lockdown, inert)
                }
            }

            lockdown = tips(R.string.access_control_lockdown_tips)
            inert = tips(R.string.access_control_all_apps_tips)

            applyMode(initialMode, lockdown, inert)
        }

        binding.modeHeader.addView(header.root)
    }

    /**
     * Exactly one of the two lines is visible at a time.
     *
     * In `AcceptAll` nothing on the list is applied to the tunnel, so the rows are
     * dimmed and inert and the line says where the selection would take effect —
     * a checkbox that stores a choice with no consequence is the control lying
     * about what it does. The stored selection is not cleared: switching to a
     * selective mode brings it back as it was.
     *
     * The other line warns that the selective modes send traffic outside the
     * tunnel. It is phrased as a condition — the app does not read Android's
     * lockdown setting and must not claim to have detected it.
     */
    private fun applyMode(
        mode: AccessControlMode,
        lockdown: TipsPreference?,
        inert: TipsPreference?,
    ) {
        val selectable = mode != AccessControlMode.AcceptAll

        lockdown?.view?.visibility = if (selectable) View.VISIBLE else View.GONE
        inert?.view?.visibility = if (selectable) View.GONE else View.VISIBLE

        listSelectable = selectable
        adapter.selectable = selectable
        adapter.rebindAll()
    }

    private suspend fun requestSearch() {
        coroutineScope {
            val binding = DialogSearchBinding
                .inflate(context.layoutInflater, context.root, false)
            // Same rule as the list behind it: search is another way to reach the
            // same rows, not an exemption from the mode.
            val adapter = AppAdapter(context, selected).apply {
                selectable = listSelectable
            }
            val dialog = FullScreenDialog(context)
            val filter = Channel<Unit>(Channel.CONFLATED)

            dialog.setContentView(binding.root)

            binding.surface = dialog.surface
            binding.mainList.applyLinearAdapter(context, adapter)
            binding.keywordView.addTextChangedListener {
                filter.trySend(Unit)
            }
            binding.closeView.setOnClickListener {
                dialog.dismiss()
            }

            dialog.setOnDismissListener {
                cancel()
            }

            dialog.setOnShowListener {
                binding.keywordView.requestTextInput()
            }

            dialog.show()

            while (isActive) {
                filter.receive()

                val keyword = binding.keywordView.text?.toString() ?: ""

                val apps: List<AppInfo> = if (keyword.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        apps.filter {
                            it.label.contains(keyword, ignoreCase = true) ||
                                    it.packageName.contains(keyword, ignoreCase = true)
                        }
                    }
                }

                adapter.patchDataSet(adapter::apps, apps, false, AppInfo::packageName)

                delay(200)
            }
        }
    }
}