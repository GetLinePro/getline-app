package com.github.kr328.clash.design

import android.content.Context
import android.net.Uri
import android.view.View
import com.github.kr328.clash.design.databinding.DesignSettingsCommonBinding
import com.github.kr328.clash.design.preference.category
import com.github.kr328.clash.design.preference.clickable
import com.github.kr328.clash.design.preference.preferenceScreen
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class HelpDesign(
    context: Context,
    openLink: (Uri) -> Unit,
    openAbout: () -> Unit,
    /**
     * Link target for the account row, resolved per environment (prod/e2e) by
     * HelpActivity. Not shown on screen — the row carries a static summary, and
     * the running environment is reported by the diagnostic report instead.
     */
    accountPortalUrl: String = context.getString(R.string.getline_account_url),
    /** GL-19: always-available path to build/share a safe diagnostic report. */
    openSendDiagnostics: (() -> Unit)? = null,
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
            category(R.string.support)

            clickable(
                title = R.string.contact_support,
                icon = R.drawable.ic_baseline_help_center,
                summary = R.string.help_support_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_support_url)))
                }
            }

            val sendDiagnostics = openSendDiagnostics
            if (sendDiagnostics != null) {
                clickable(
                    title = R.string.send_diagnostics,
                    icon = R.drawable.ic_baseline_assignment,
                    summary = R.string.help_diagnostics_summary,
                ) {
                    clicked {
                        sendDiagnostics()
                    }
                }
            }

            clickable(
                title = R.string.privacy_policy,
                icon = R.drawable.ic_outline_article,
                summary = R.string.help_privacy_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_privacy_url)))
                }
            }

            clickable(
                title = R.string.getline_account,
                icon = R.drawable.ic_baseline_domain,
                summary = R.string.help_account_summary,
            ) {
                clicked {
                    openLink(Uri.parse(accountUrl))
                }
            }

            category(R.string.app)

            clickable(
                title = R.string.about,
                icon = R.drawable.ic_baseline_info,
                summary = R.string.help_about_summary,
            ) {
                clicked {
                    openAbout()
                }
            }

            category(R.string.sources)

            clickable(
                title = R.string.getline_sources_title,
                icon = R.drawable.ic_baseline_stack,
                summary = R.string.help_getline_sources_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.getline_sources_url)))
                }
            }

            clickable(
                title = R.string.clash_meta_for_android,
                icon = R.drawable.ic_baseline_meta,
                summary = R.string.help_cmfa_sources_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.meta_github_url)))
                }
            }

            clickable(
                title = R.string.clash_meta_core,
                icon = R.drawable.ic_baseline_dns,
                summary = R.string.help_mihomo_sources_summary,
            ) {
                clicked {
                    openLink(Uri.parse(context.getString(R.string.clash_meta_core_url)))
                }
            }
        }

        binding.content.addView(screen.root)
    }
}
