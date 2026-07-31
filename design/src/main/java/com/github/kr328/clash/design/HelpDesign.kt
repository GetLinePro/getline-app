package com.github.kr328.clash.design

import android.content.Context
import android.net.Uri
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.preference.tips
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class HelpDesign(
    context: Context,
    openLink: (Uri) -> Unit,
    openAbout: () -> Unit,
    /**
     * Account portal origin for this app environment (prod/e2e). Prefer an
     * app-level override of [R.string.getline_account_url]; this parameter is
     * the explicit entry point from HelpActivity when the string is injected.
     */
    accountPortalUrl: String = context.getString(R.string.getline_account_url),
) : Design<Unit>(context) {
    private val binding = DesignSettingsCommonBinding
        .inflate(context.layoutInflater, context.root, false)

    override val root: View
        get() = binding.root

    init {
        binding.surface = surface

        binding.activityBarLayout.applyFrom(context)

        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)

        val accountUrl = accountPortalUrl.trim().ifEmpty {
            context.getString(R.string.getline_account_url)
        }

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
            ) {
                summary = accountUrl
                clicked {
                    openLink(Uri.parse(accountUrl))
                }
            }

            category(R.string.sources)

            clickable(
                title = R.string.getline_sources_title,
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

            category(R.string.about)

            clickable(
                title = R.string.about,
            ) {
                clicked {
                    openAbout()
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
