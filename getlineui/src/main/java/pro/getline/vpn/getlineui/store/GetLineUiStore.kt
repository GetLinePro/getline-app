package pro.getline.vpn.getlineui.store

import android.content.Context
import com.github.kr328.clash.common.store.Store
import com.github.kr328.clash.common.store.asStoreProvider

/**
 * Product-only UI prefs. Reads the same SharedPreferences file and keys as the
 * legacy CMFA UI preference store so existing installs keep shell tab and
 * recents settings. Does not touch CMFA-only keys.
 */
class GetLineUiStore(context: Context) {
    private val store = Store(
        context
            .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
            .asStoreProvider()
    )

    var hideFromRecents: Boolean by store.boolean(
        key = "hide_from_recents",
        defaultValue = false,
    )

    /**
     * Persisted product-shell tab: home | servers | subscription.
     * Default home so cold start always lands on the primary destination.
     */
    var getLineShellTab: String by store.string(
        key = "get_line_shell_tab",
        defaultValue = "home",
    )

    companion object {
        private const val PREFERENCE_NAME = "ui"
    }
}
