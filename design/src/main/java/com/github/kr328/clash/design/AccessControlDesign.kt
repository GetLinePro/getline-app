package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.kr328.clash.design.adapter.AppAdapter
import com.github.kr328.clash.design.component.AccessControlMenu
import com.github.kr328.clash.design.databinding.DesignAccessControlBinding
import com.github.kr328.clash.design.databinding.DialogSearchBinding
import com.github.kr328.clash.design.dialog.FullScreenDialog
import com.github.kr328.clash.design.model.AppInfo
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
        //
        // LinearLayoutManager keeps the current child's screen coordinate when
        // padding changes, so item 0 stays put while the caption grows over it.
        // If the list is already at the logical start, re-anchor it after the
        // new padding; a scrolled list is left alone.
        binding.activityBarLayout.addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
            val height = bottom - top

            view.post {
                val list = binding.mainList

                if (list.paddingTop != height) {
                    val wasAtTop = !list.canScrollVertically(-1)

                    list.updatePadding(top = height)

                    if (wasAtTop && adapter.itemCount > 0) {
                        (list.layoutManager as LinearLayoutManager)
                            .scrollToPositionWithOffset(0, 0)
                    }
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
     * here. AccessControlActivity requests a TUN reconcile on the way out, so a
     * user still making up their mind does not rebuild the interface on every tap.
     *
     * The initial check is applied before the listener so restoring the stored
     * mode is not treated as a user change.
     */
    private fun bindModeHeader(srvStore: ServiceStore, initialMode: AccessControlMode) {
        binding.accessControlModeGroup.check(buttonId(initialMode))
        applyMode(initialMode)

        binding.accessControlModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeForButton(checkedId) ?: return@setOnCheckedChangeListener
            srvStore.accessControlMode = mode
            applyMode(mode)
        }
    }

    /**
     * One caption, two texts.
     *
     * In `AcceptAll` nothing on the list is applied to the tunnel, so the rows are
     * dimmed and inert and the caption says the list is unused. The stored
     * selection is not cleared: switching to a selective mode brings it back as
     * it was.
     *
     * The other text warns that the selective modes send traffic outside the
     * tunnel. It is phrased as a condition — the app does not read Android's
     * lockdown setting and must not claim to have detected it.
     */
    private fun applyMode(mode: AccessControlMode) {
        val selectable = mode != AccessControlMode.AcceptAll

        binding.accessControlModeCaption.setText(
            if (selectable) {
                R.string.access_control_lockdown_tips
            } else {
                R.string.access_control_all_apps_tips
            }
        )

        listSelectable = selectable
        adapter.selectable = selectable
        adapter.rebindAll()
    }

    private fun buttonId(mode: AccessControlMode): Int = when (mode) {
        AccessControlMode.AcceptAll -> R.id.access_control_mode_all
        AccessControlMode.AcceptSelected -> R.id.access_control_mode_selected
        AccessControlMode.DenySelected -> R.id.access_control_mode_except
    }

    private fun modeForButton(id: Int): AccessControlMode? = when (id) {
        R.id.access_control_mode_all -> AccessControlMode.AcceptAll
        R.id.access_control_mode_selected -> AccessControlMode.AcceptSelected
        R.id.access_control_mode_except -> AccessControlMode.DenySelected
        else -> null
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