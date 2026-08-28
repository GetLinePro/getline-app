package pro.getline.vpn.getlineui

import android.content.Context
import android.text.InputType
import android.view.View
import pro.getline.vpn.getlineui.databinding.DesignGetLineSettingsBinding
import pro.getline.vpn.getlineui.dialog.requestModelTextInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The GetLine Settings screen. Today it holds one section — the local network
 * proxy — and it renders exactly what it is given: this class knows no facade,
 * no runtime and no clipboard. It reports what the user asked for and waits to
 * be told the new state, so the screen can never disagree with the session
 * that owns the listener.
 *
 * Values are handed over as strings and returned as typed text. Validation
 * belongs to the module that persists them: a screen that decided for itself
 * what a valid port is would be a second, quietly diverging authority.
 */
class GetLineSettingsDesign(context: Context) :
    GetLineScreen<GetLineSettingsDesign.Request>(context) {

    enum class Field { Address, Port, Username, Password }

    sealed interface Request {
        object Back : Request

        object Enable : Request

        object Disable : Request

        /**
         * Put the current value of [field] on the clipboard. The value is not
         * carried: the host copies from the state it holds, so what lands on
         * the clipboard is the authoritative value and not a rendered string.
         */
        data class Copy(val field: Field) : Request

        /** Raw text exactly as typed, for the host to validate and persist. */
        data class Edit(val field: Field, val value: String) : Request
    }

    /** What the proxy is doing, in the screen's own vocabulary. */
    enum class Status { Loading, Disabled, Enabling, Disabling, Active }

    /** The one line under the action button explaining why it cannot be used. */
    enum class Hint { None, ConnectVpnFirst, LockedWhileActive, StorageUnavailable }

    data class State(
        val status: Status = Status.Loading,
        /** The endpoint a LAN client dials; null unless a listener is bound. */
        val address: String? = null,
        val port: String? = null,
        val username: String? = null,
        val password: String? = null,
        val actionEnabled: Boolean = false,
        val hint: Hint = Hint.None,
    )

    private val binding = DesignGetLineSettingsBinding
        .inflate(layoutInflater, contentRoot, false)

    override val root: View
        get() = binding.root

    private var state = State()

    /**
     * Reveal is per-visit and per-value: it is cleared whenever the password
     * changes and by the host when the screen leaves the foreground, so a
     * revealed secret never survives into a later look at the screen.
     */
    private var passwordRevealed = false

    /** One edit dialog at a time; a second tap must not stack another. */
    private var editing = false

    init {
        binding.self = this
        apply(state)
    }

    suspend fun render(state: State) {
        // immediate: the collector already runs on the main thread, and a
        // posted render would leave one frame showing the previous state.
        withContext(Dispatchers.Main.immediate) {
            if (state.password != this@GetLineSettingsDesign.state.password) {
                passwordRevealed = false
            }

            apply(state)
        }
    }

    /** Re-masks the password, e.g. when the screen stops. */
    suspend fun hidePassword() {
        withContext(Dispatchers.Main.immediate) {
            if (!passwordRevealed) return@withContext

            passwordRevealed = false

            apply(state)
        }
    }

    fun onBack() {
        request(Request.Back)
    }

    fun onPrimaryAction() {
        request(if (isRunning(state.status)) Request.Disable else Request.Enable)
    }

    fun togglePasswordRevealed() {
        passwordRevealed = !passwordRevealed

        apply(state)
    }

    fun copyAddress() = request(Request.Copy(Field.Address))

    fun copyPort() = request(Request.Copy(Field.Port))

    fun copyUsername() = request(Request.Copy(Field.Username))

    fun copyPassword() = request(Request.Copy(Field.Password))

    fun editPort() = edit(
        field = Field.Port,
        title = R.string.get_line_settings_field_port,
        current = state.port,
        // A port is digits; the text keyboard would put a comma and a full
        // stop under the thumb and the digits behind a shift.
        inputType = InputType.TYPE_CLASS_NUMBER,
    )

    fun editUsername() = edit(
        field = Field.Username,
        title = R.string.get_line_settings_field_username,
        current = state.username,
        // Visible password, not plain text: no autocorrect, no suggestion
        // strip, no capitalisation of a credential.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    )

    fun editPassword() = edit(
        field = Field.Password,
        title = R.string.get_line_settings_field_password,
        current = state.password,
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
    )

    private fun edit(field: Field, title: Int, current: String?, inputType: Int) {
        if (!isEditable(state.status) || current == null || editing) return

        editing = true

        launch {
            try {
                val entered = context.requestModelTextInput(
                    initial = current,
                    title = context.getString(title),
                    hint = context.getString(title),
                    inputType = inputType,
                )

                // The dialog answers with the initial value when it is
                // cancelled, so an unchanged value is not a request to save.
                if (entered != current) {
                    request(Request.Edit(field, entered))
                }
            } finally {
                editing = false
            }
        }
    }

    fun request(request: Request) {
        requests.trySend(request)
    }

    private fun apply(state: State) {
        this.state = state

        val editable = isEditable(state.status)
        val running = isRunning(state.status)

        binding.statusText = context.getString(
            when (state.status) {
                Status.Loading -> R.string.get_line_settings_status_loading
                Status.Disabled -> R.string.get_line_settings_status_disabled
                Status.Enabling -> R.string.get_line_settings_status_enabling
                Status.Disabling -> R.string.get_line_settings_status_disabling
                Status.Active -> R.string.get_line_settings_status_active
            },
        )

        binding.addressText = state.address ?: ""
        binding.addressVisible = state.address != null

        // The card appears with the settings, not before them: empty rows
        // under a "Checking…" line would look like values the user could act
        // on.
        binding.fieldsVisible = state.port != null
        binding.fieldsEditable = editable
        binding.portText = state.port ?: ""
        binding.usernameText = state.username ?: ""
        binding.passwordText = when {
            state.password == null -> ""
            passwordRevealed -> state.password
            else -> MASK
        }
        binding.passwordRevealed = passwordRevealed

        binding.actionText = context.getString(
            if (running) {
                R.string.get_line_settings_disable
            } else {
                R.string.get_line_settings_enable
            },
        )
        binding.actionEnabled = state.actionEnabled

        binding.hintVisible = state.hint != Hint.None
        binding.hintText = when (state.hint) {
            Hint.None -> ""
            Hint.ConnectVpnFirst -> context.getString(R.string.get_line_settings_connect_vpn_first)
            Hint.LockedWhileActive ->
                context.getString(R.string.get_line_settings_locked_while_active)
            Hint.StorageUnavailable ->
                context.getString(R.string.get_line_settings_storage_unavailable)
        }

        binding.executePendingBindings()
    }

    /**
     * Editing is offered only in the one state where nothing is bound to these
     * credentials and the answer is already known. `Loading` is deliberately
     * not editable: the screen has not yet been told whether a listener is
     * running on exactly these values.
     */
    private fun isEditable(status: Status): Boolean = status == Status.Disabled

    /** `Disabling` counts as running: the button that started it stays "Turn off". */
    private fun isRunning(status: Status): Boolean =
        status == Status.Active || status == Status.Disabling

    private companion object {
        const val MASK = "••••••••"
    }
}
