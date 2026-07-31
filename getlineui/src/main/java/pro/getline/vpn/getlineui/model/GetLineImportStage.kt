package pro.getline.vpn.getlineui.model

import androidx.annotation.StringRes
import pro.getline.vpn.getlineui.R

/**
 * Product-facing stages during headless subscription import.
 * Only values that the CMFA fetch path actually emits today.
 */
enum class GetLineImportStage(@StringRes val label: Int) {
    /** Login only, and only for accounts that turned out to have no subscription. */
    ActivatingTrial(R.string.get_line_fetch_activating_trial),
    LoadingConfig(R.string.get_line_fetch_loading_config),
    Checking(R.string.get_line_fetch_checking),
}
