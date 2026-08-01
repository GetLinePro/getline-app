package pro.getline.vpn.getlineui.view

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.core.content.ContextCompat
import pro.getline.vpn.getlineui.R
import pro.getline.vpn.getlineui.databinding.ComponentGetLineStateBinding
import pro.getline.vpn.getlineui.model.GetLineProductState
import pro.getline.vpn.getlineui.model.GetLineRecoveryAction
import com.google.android.material.card.MaterialCardView

class GetLineStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {
    private val binding = ComponentGetLineStateBinding.inflate(
        LayoutInflater.from(context),
        this,
        true,
    )
    private var recoveryAction = GetLineRecoveryAction.None
    private var onRecoveryAction: ((GetLineRecoveryAction) -> Unit)? = null
    private var onSendDiagnostics: (() -> Unit)? = null

    init {
        radius = 16 * resources.displayMetrics.density
        cardElevation = 0f
        strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
        // Hairline, not brand cyan — a state card is not an accent.
        strokeColor = ContextCompat.getColor(context, R.color.getline_hairline)
        setCardBackgroundColor(
            ContextCompat.getColor(context, R.color.getline_brand_surface)
        )
        binding.action.setOnClickListener {
            onRecoveryAction?.invoke(recoveryAction)
        }
        binding.sendDiagnostics.setOnClickListener {
            onSendDiagnostics?.invoke()
        }
        visibility = View.GONE
    }

    fun setOnRecoveryAction(listener: (GetLineRecoveryAction) -> Unit) {
        onRecoveryAction = listener
    }

    fun setOnSendDiagnostics(listener: () -> Unit) {
        onSendDiagnostics = listener
    }

    fun render(
        state: GetLineProductState,
        action: GetLineRecoveryAction = GetLineRecoveryAction.None,
        showSendDiagnostics: Boolean = false,
    ) {
        visibility = if (state == GetLineProductState.Content) View.GONE else View.VISIBLE
        if (state == GetLineProductState.Content)
            return

        recoveryAction = action
        binding.title.setText(state.title)
        binding.explanation.setText(state.explanation)
        binding.progress.visibility = if (state.loading) View.VISIBLE else View.GONE
        binding.action.visibility =
            if (action == GetLineRecoveryAction.None) View.GONE else View.VISIBLE
        if (action != GetLineRecoveryAction.None) {
            binding.action.setText(action.label)
        }
        binding.sendDiagnostics.visibility =
            if (showSendDiagnostics) View.VISIBLE else View.GONE
    }

    /**
     * Generic message card for destinations that are not [GetLineProductState]
     * (e.g. Subscription empty / signed-out / failed).
     */
    fun renderMessage(
        @androidx.annotation.StringRes title: Int,
        @androidx.annotation.StringRes explanation: Int,
        loading: Boolean = false,
        action: GetLineRecoveryAction = GetLineRecoveryAction.None,
        showSendDiagnostics: Boolean = false,
    ) {
        visibility = View.VISIBLE
        recoveryAction = action
        binding.title.setText(title)
        binding.explanation.setText(explanation)
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.action.visibility =
            if (action == GetLineRecoveryAction.None) View.GONE else View.VISIBLE
        if (action != GetLineRecoveryAction.None) {
            binding.action.setText(action.label)
        }
        binding.sendDiagnostics.visibility =
            if (showSendDiagnostics) View.VISIBLE else View.GONE
    }

    fun hide() {
        visibility = View.GONE
    }
}
