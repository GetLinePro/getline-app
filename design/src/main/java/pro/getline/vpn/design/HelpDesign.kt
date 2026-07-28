package pro.getline.vpn.design

import android.content.Context
import android.net.Uri
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

class HelpDesign(
    context: Context,
    openLink: (Uri) -> Unit,
) : Design<Unit>(context) {
    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val screen = preferenceScreen(context) {
            tips(R.string.tips_help)

            category(R.string.support)

            clickable(
                title = R.string.support,
                summary = R.string.getline_support_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_support_url)))
                }
            }

            clickable(
                title = R.string.privacy_policy,
                summary = R.string.getline_privacy_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_privacy_url)))
                }
            }

            clickable(
                title = R.string.getline_account,
                summary = R.string.getline_account_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_account_url)))
                }
            }

            category(R.string.sources)

            clickable(
                title = R.string.get_line_vpn,
                summary = R.string.getline_sources_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_sources_url)))
                }
            }

            clickable(
                title = R.string.clash_meta_for_android,
                summary = R.string.meta_github_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.meta_github_url)))
                }
            }

            clickable(
                title = R.string.clash_meta_core,
                summary = R.string.clash_meta_core_url
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.clash_meta_core_url)))
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
