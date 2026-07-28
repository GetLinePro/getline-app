package pro.getline.vpn.design.model

import androidx.annotation.StringRes
import pro.getline.vpn.core.model.FetchStatus
import pro.getline.vpn.design.R

/**
 * Maps CMFA [FetchStatus] stages to stable GetLine user-facing vocabulary.
 * Provider names, URLs, and raw CMFA labels stay in logs — not on the dialog.
 */
object GetLineFetchStatusCopy {
    enum class Stage {
        /** Getting configuration (config + provider fetch). */
        LoadingConfig,
        /** Checking / verifying settings. */
        Checking,
        /** Updating connection (reserved for future product progress). */
        Updating,
        /** Starting VPN core (reserved for future product progress). */
        StartingVpn,
        /** Connection ready (reserved for future product progress). */
        Ready,
    }

    fun stageOf(action: FetchStatus.Action): Stage? {
        return when (action) {
            FetchStatus.Action.FetchConfiguration,
            FetchStatus.Action.FetchProviders -> Stage.LoadingConfig
            FetchStatus.Action.Verifying -> Stage.Checking
            FetchStatus.Action.SubscriptionInfo -> null
        }
    }

    @StringRes
    fun stringRes(stage: Stage): Int {
        return when (stage) {
            Stage.LoadingConfig -> R.string.get_line_fetch_loading_config
            Stage.Checking -> R.string.get_line_fetch_checking
            Stage.Updating -> R.string.get_line_fetch_updating
            Stage.StartingVpn -> R.string.get_line_fetch_starting_vpn
            Stage.Ready -> R.string.get_line_fetch_ready
        }
    }

    /** Compact diagnostic line for logs (raw CMFA stage + args). */
    fun diagnosticLine(status: FetchStatus): String {
        return "fetch action=${status.action} args=${status.args} " +
            "progress=${status.progress}/${status.max}"
    }
}
