package pro.getline.vpn

import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import pro.getline.vpn.getline.clipboard.SensitiveClipboard
import pro.getline.vpn.getline.localproxy.LocalLanProxy
import pro.getline.vpn.getline.localproxy.LocalLanProxyFacade
import pro.getline.vpn.getline.localproxy.LocalLanProxyResult
import pro.getline.vpn.getline.localproxy.LocalLanProxySettingsPresentation
import pro.getline.vpn.getlineui.GetLineSettingsDesign
import pro.getline.vpn.getlineui.ToastDuration
import pro.getline.vpn.product.GetLineActivity
import pro.getline.vpn.getlineui.R as GetLineUiR

/**
 * GetLine Settings. Opened from the gear in Home's header; today it holds the
 * local network proxy and nothing else.
 *
 * Everything it knows about the proxy comes from [LocalLanProxy]: one flow and
 * three suspend calls. It never starts the VPN, never talks to the runtime or
 * the status transport, and never decides whether a listener is up — it
 * renders what the facade publishes, which is why reopening it (or coming back
 * after the process was killed) shows the live state rather than the result of
 * the last call it happened to make.
 */
class GetLineSettingsActivity : GetLineActivity<GetLineSettingsDesign>() {
    private val proxy: LocalLanProxy by lazy { LocalLanProxyFacade.get(this) }

    /**
     * The enable/disable call in flight. Kept so a second tap is dropped
     * rather than queued: the facade serializes its transactions, and a queued
     * second one would act on a state the user can no longer see.
     */
    private var transaction: Job? = null

    override suspend fun main() {
        val design = GetLineSettingsDesign(this)

        setContentDesign(design)

        launch {
            proxy.state.collect {
                design.render(LocalLanProxySettingsPresentation.present(it))
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive { event ->
                    // Leaving the screen re-masks the password: a revealed
                    // secret should not be waiting in the recents preview or
                    // for whoever picks the phone up next.
                    if (event == Event.ActivityStop) design.hidePassword()
                }
                design.requests.onReceive { handle(it, design) }
            }
        }
    }

    private suspend fun handle(request: GetLineSettingsDesign.Request, design: GetLineSettingsDesign) {
        when (request) {
            GetLineSettingsDesign.Request.Back -> finish()
            GetLineSettingsDesign.Request.Enable -> runTransaction { proxy.enable() }
            GetLineSettingsDesign.Request.Disable -> runTransaction { proxy.disable() }
            is GetLineSettingsDesign.Request.Copy -> copy(request.field, design)
            is GetLineSettingsDesign.Request.Edit -> edit(request, design)
        }
    }

    /**
     * Runs the call off the request loop so the screen stays usable while a
     * listener is coming up or going down — Back in particular. The transitional
     * status the facade publishes is what tells the user something is happening.
     */
    private fun runTransaction(block: suspend () -> LocalLanProxyResult) {
        if (transaction?.isActive == true) return

        transaction = launch {
            val result = block()

            report(result)
        }
    }

    private suspend fun copy(field: GetLineSettingsDesign.Field, design: GetLineSettingsDesign) {
        val value = LocalLanProxySettingsPresentation.copyValue(proxy.state.value, field)
            ?: return

        val copied = SensitiveClipboard.copy(
            context = this,
            label = getString(LocalLanProxySettingsPresentation.label(field)),
            value = value,
            confirmation = GetLineUiR.string.get_line_settings_copied,
        )

        if (!copied) {
            design.showToast(
                GetLineUiR.string.get_line_settings_copy_failed,
                ToastDuration.Short,
            )
        }
    }

    private suspend fun edit(
        request: GetLineSettingsDesign.Request.Edit,
        design: GetLineSettingsDesign,
    ) {
        val config = proxy.state.value.config
            ?: return design.showToast(
                GetLineUiR.string.get_line_settings_error_storage,
                ToastDuration.Long,
            )

        val edited = LocalLanProxySettingsPresentation.edited(config, request.field, request.value)
            ?: return design.showToast(
                GetLineUiR.string.get_line_settings_error_invalid_port,
                ToastDuration.Long,
            )

        report(proxy.updateConfig(edited))
    }

    private suspend fun report(result: LocalLanProxyResult) {
        val message = LocalLanProxySettingsPresentation.message(result) ?: return

        // The design is gone once the activity is destroyed; a result that
        // arrives after that has nowhere to be shown, and the facade has
        // already published the state it produced.
        val design = design ?: return

        design.showToast(message, ToastDuration.Long)
    }
}
