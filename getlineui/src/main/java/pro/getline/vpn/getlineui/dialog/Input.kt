package pro.getline.vpn.getlineui.dialog

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import pro.getline.vpn.getlineui.R
import pro.getline.vpn.getlineui.databinding.DialogGetLineTextFieldBinding
import pro.getline.vpn.getlineui.util.Validator
import pro.getline.vpn.getlineui.util.ValidatorAcceptAll
import pro.getline.vpn.getlineui.util.layoutInflater
import pro.getline.vpn.getlineui.util.requestTextInput
import pro.getline.vpn.getlineui.util.root
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

suspend fun Context.requestModelTextInput(
    initial: String,
    title: CharSequence,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
): String {
    return this.requestModelTextInput(initial, title, null, hint, error, validator)!!
}

suspend fun Context.requestModelTextInput(
    initial: String?,
    title: CharSequence,
    reset: CharSequence?,
    hint: CharSequence? = null,
    error: CharSequence? = null,
    validator: Validator = ValidatorAcceptAll,
): String? {
    return suspendCancellableCoroutine {
        // Unique layout name: must not collide with design's dialog_text_field
        // (merged R.layout + dual DataBinderMapperImpl → ClassCastException).
        val binding = DialogGetLineTextFieldBinding
            .inflate(layoutInflater, this.root, false)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(binding.root)
            .setCancelable(true)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = binding.textField.text?.toString() ?: ""

                if (validator(text))
                    it.resume(text)
                else
                    it.resume(initial)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }
            .setOnDismissListener { _ ->
                if (!it.isCompleted)
                    it.resume(initial)
            }

        if (reset != null) {
            builder.setNeutralButton(reset) { _, _ ->
                it.resume(null)
            }
        }

        val dialog = builder.create()

        it.invokeOnCancellation {
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            if (hint != null)
                binding.textLayout.hint = hint

            binding.textField.apply {
                binding.textLayout.isErrorEnabled = error != null

                // An empty field is "not filled in yet", not a mistake: keep OK
                // disabled but do not paint the field red before the user typed.
                // setText(initial) below runs this too, which is what used to show
                // the error on a dialog the user had not touched.
                fun applyValidation(text: String) {
                    val valid = validator(text)
                    if (error != null) {
                        binding.textLayout.error = error.takeIf { !valid && text.isNotEmpty() }
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = valid
                }

                doOnTextChanged { text, _, _, _ ->
                    applyValidation(text?.toString() ?: "")
                }

                setText(initial)
                applyValidation(initial ?: "")

                setSelection(0, initial?.length ?: 0)

                requestTextInput()
            }
        }

        dialog.show()
    }
}
