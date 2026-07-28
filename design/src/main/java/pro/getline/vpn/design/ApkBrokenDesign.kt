package pro.getline.vpn.design

import android.content.Context
import android.view.View
import pro.getline.vpn.design.databinding.DesignSettingsCommonBinding
import pro.getline.vpn.design.preference.category
import pro.getline.vpn.design.preference.clickable
import pro.getline.vpn.design.preference.preferenceScreen
import pro.getline.vpn.design.preference.tips
import pro.getline.vpn.design.util.applyFrom
import pro.getline.vpn.design.util.bindAppBarElevation
import pro.getline.vpn.design.util.layoutInflater
import pro.getline.vpn.design.util.root

class ApkBrokenDesign(context: Context) : Design<ApkBrokenDesign.Request>(context) {
    data class Request(val url: String)

    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            tips(R.string.application_broken_tips)

            category(R.string.reinstall)

            clickable(
                title = R.string.github_releases,
                summary = R.string.getline_sources_url
            ) {
                clicked {
                    requests.trySend(Request(context.getString(R.string.getline_sources_url)))
                }
            }
        }

        binding.content.addView(screen.root)
    }
}