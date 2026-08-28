package pro.getline.vpn.getline.localproxy

import pro.getline.vpn.getlineui.GetLineSettingsDesign
import pro.getline.vpn.getlineui.R as GetLineUiR

/**
 * The one place a [LocalLanProxySnapshot] becomes something to draw, and a
 * [LocalLanProxyResult] becomes a sentence.
 *
 * It is deliberately not in the design: the screen is not allowed to know the
 * product models, and the facade is not allowed to know about strings. Pure
 * and total, so every state the facade can publish has a defined rendering
 * rather than a branch nobody ran.
 */
object LocalLanProxySettingsPresentation {
    fun present(snapshot: LocalLanProxySnapshot): GetLineSettingsDesign.State {
        val status = snapshot.status
        val active = status as? LocalLanProxyStatus.Active

        return GetLineSettingsDesign.State(
            status = when (status) {
                LocalLanProxyStatus.Loading -> GetLineSettingsDesign.Status.Loading
                LocalLanProxyStatus.Disabled -> GetLineSettingsDesign.Status.Disabled
                LocalLanProxyStatus.Enabling -> GetLineSettingsDesign.Status.Enabling
                LocalLanProxyStatus.Disabling -> GetLineSettingsDesign.Status.Disabling
                is LocalLanProxyStatus.Active -> GetLineSettingsDesign.Status.Active
            },
            address = active?.address,
            // While a listener is bound its own port is the truth: the saved
            // value cannot be edited in that state anyway, and showing the
            // bound one keeps the row and the address describing one endpoint.
            port = (active?.port ?: snapshot.config?.port)?.toString(),
            username = snapshot.config?.username,
            password = snapshot.config?.password,
            actionEnabled = when (status) {
                // Turning it off must stay available even when the settings
                // store has since become unreadable: the listener is up.
                is LocalLanProxyStatus.Active -> true
                LocalLanProxyStatus.Disabled ->
                    snapshot.availability == LocalLanProxyAvailability.Ready
                // A transaction, or an answer that has not arrived yet.
                LocalLanProxyStatus.Loading,
                LocalLanProxyStatus.Enabling,
                LocalLanProxyStatus.Disabling,
                -> false
            },
            hint = when {
                snapshot.availability == LocalLanProxyAvailability.SettingsUnavailable ->
                    GetLineSettingsDesign.Hint.StorageUnavailable
                status is LocalLanProxyStatus.Active ->
                    GetLineSettingsDesign.Hint.LockedWhileActive
                status == LocalLanProxyStatus.Disabled &&
                    snapshot.availability == LocalLanProxyAvailability.VpnOffline ->
                    GetLineSettingsDesign.Hint.ConnectVpnFirst
                else -> GetLineSettingsDesign.Hint.None
            },
        )
    }

    /** The sentence for a finished call, or null when there is nothing to say. */
    fun message(result: LocalLanProxyResult): Int? = when (result) {
        LocalLanProxyResult.Success -> null
        LocalLanProxyResult.VpnUnavailable ->
            GetLineUiR.string.get_line_settings_error_vpn_unavailable
        LocalLanProxyResult.NoEligibleLan ->
            GetLineUiR.string.get_line_settings_error_no_lan
        LocalLanProxyResult.PortOccupied ->
            GetLineUiR.string.get_line_settings_error_port_occupied
        LocalLanProxyResult.ActiveNotEditable ->
            GetLineUiR.string.get_line_settings_locked_while_active
        LocalLanProxyResult.SettingsUnavailable ->
            GetLineUiR.string.get_line_settings_error_storage
        LocalLanProxyResult.ApplyFailed ->
            GetLineUiR.string.get_line_settings_error_apply_failed
        LocalLanProxyResult.SafetyStop ->
            GetLineUiR.string.get_line_settings_error_safety_stop
        is LocalLanProxyResult.InvalidSettings -> when (result.field) {
            LocalLanProxyResult.InvalidSettings.Field.Port ->
                GetLineUiR.string.get_line_settings_error_invalid_port
            LocalLanProxyResult.InvalidSettings.Field.Username ->
                GetLineUiR.string.get_line_settings_error_invalid_username
            LocalLanProxyResult.InvalidSettings.Field.Password ->
                GetLineUiR.string.get_line_settings_error_invalid_password
        }
    }

    /** The label of a field, used both on screen and as the clipboard label. */
    fun label(field: GetLineSettingsDesign.Field): Int = when (field) {
        GetLineSettingsDesign.Field.Address -> GetLineUiR.string.get_line_settings_field_address
        GetLineSettingsDesign.Field.Port -> GetLineUiR.string.get_line_settings_field_port
        GetLineSettingsDesign.Field.Username -> GetLineUiR.string.get_line_settings_field_username
        GetLineSettingsDesign.Field.Password -> GetLineUiR.string.get_line_settings_field_password
    }

    /**
     * The value a copy action puts on the clipboard, taken from the snapshot
     * rather than from what is drawn — the password row shows a mask, and the
     * mask is not what anyone wants pasted.
     */
    fun copyValue(
        snapshot: LocalLanProxySnapshot,
        field: GetLineSettingsDesign.Field,
    ): String? {
        val config = snapshot.config

        return when (field) {
            GetLineSettingsDesign.Field.Address ->
                (snapshot.status as? LocalLanProxyStatus.Active)?.address
            GetLineSettingsDesign.Field.Port ->
                ((snapshot.status as? LocalLanProxyStatus.Active)?.port ?: config?.port)?.toString()
            GetLineSettingsDesign.Field.Username -> config?.username
            GetLineSettingsDesign.Field.Password -> config?.password
        }
    }

    /**
     * The edited config, or null when the text cannot become one. A port that
     * is not a number is refused here because it cannot even be carried to the
     * validator; every other rule stays with the facade.
     */
    fun edited(
        config: LocalLanProxyUserConfig,
        field: GetLineSettingsDesign.Field,
        value: String,
    ): LocalLanProxyUserConfig? = when (field) {
        GetLineSettingsDesign.Field.Port ->
            value.trim().toIntOrNull()?.let { config.copy(port = it) }
        GetLineSettingsDesign.Field.Username -> config.copy(username = value)
        GetLineSettingsDesign.Field.Password -> config.copy(password = value)
        // Not editable: it is the address the runtime bound, not a setting.
        GetLineSettingsDesign.Field.Address -> null
    }
}
