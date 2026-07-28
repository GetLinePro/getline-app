package pro.getline.vpn.design

import android.content.Context
import android.view.View
import pro.getline.vpn.design.databinding.DesignSettingsCommonBinding
import pro.getline.vpn.design.model.Behavior
import pro.getline.vpn.design.preference.*
import pro.getline.vpn.design.store.UiStore
import pro.getline.vpn.design.util.applyFrom
import pro.getline.vpn.design.util.bindAppBarElevation
import pro.getline.vpn.design.util.layoutInflater
import pro.getline.vpn.design.util.root
import pro.getline.vpn.service.store.ServiceStore

class AppSettingsDesign(
    context: Context,
    uiStore: UiStore,
    srvStore: ServiceStore,
    behavior: Behavior,
    running: Boolean,
    onHideIconChange: (hide: Boolean) -> Unit,
) : Design<AppSettingsDesign.Request>(context) {
    enum class Request {
        ReCreateAllActivities
    }

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            category(R.string.behavior)

            switch(
                value = behavior::autoRestart,
                icon = R.drawable.ic_baseline_restore,
                title = R.string.auto_restart,
                summary = R.string.allow_clash_auto_restart,
            )

            category(R.string.interface_)

            switch(
                value = uiStore::hideAppIcon,
                icon = R.drawable.ic_baseline_hide,
                title = R.string.hide_app_icon_title,
                summary = R.string.hide_app_icon_desc,
            ) {
                listener = OnChangedListener {
                    onHideIconChange(uiStore::hideAppIcon.get())
                }
            }

            switch(
                value = uiStore::hideFromRecents,
                icon = R.drawable.ic_baseline_stack,
                title = R.string.hide_from_recents_title,
                summary = R.string.hide_from_recents_desc,
            ) {
                listener = OnChangedListener {
                    requests.trySend(Request.ReCreateAllActivities)
                }
            }

            category(R.string.service)

            switch(
                value = srvStore::dynamicNotification,
                icon = R.drawable.ic_baseline_domain,
                title = R.string.show_traffic,
                summary = R.string.show_traffic_summary
            ) {
                enabled = !running
            }
        }

        binding.content.addView(screen.root)
    }
}