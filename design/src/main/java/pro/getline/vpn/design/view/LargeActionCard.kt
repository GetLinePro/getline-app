package pro.getline.vpn.design.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.graphics.drawable.DrawableCompat
import pro.getline.vpn.design.R
import pro.getline.vpn.design.databinding.ComponentLargeActionLabelBinding
import pro.getline.vpn.design.util.*
import com.google.android.material.card.MaterialCardView

class LargeActionCard @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    @AttrRes defStyleAttr: Int = 0
) : MaterialCardView(context, attributeSet, defStyleAttr) {
    private val binding = ComponentLargeActionLabelBinding
        .inflate(context.layoutInflater, this, true)

    private var iconDrawable: Drawable? = null
    private var contentColorApplied = false

    var text: CharSequence?
        get() = binding.textView.text
        set(value) {
            binding.textView.text = value
        }

    var subtext: CharSequence?
        get() = binding.subtextView.text
        set(value) {
            binding.subtextView.text = value
        }

    var icon: Drawable?
        get() = iconDrawable
        set(value) {
            iconDrawable = value?.constantState?.newDrawable()?.mutate() ?: value?.mutate()
            applyIcon()
        }

    /**
     * Optional override for title / subtitle / icon color.
     * Only applied when set explicitly (XML attr or data binding).
     * Default path keeps TextAppearance text colors and untinted vector icons —
     * do not resolve framework colorControlNormal here (may be a ColorStateList id).
     */
    @get:ColorInt
    var contentColor: Int = 0
        set(value) {
            field = value
            contentColorApplied = true
            binding.textView.setTextColor(value)
            binding.subtextView.setTextColor(value)
            applyIcon()
        }

    init {
        context.resolveClickableAttrs(attributeSet, defStyleAttr) {
            isFocusable = focusable(true)
            isClickable = clickable(true)
            foreground = foreground() ?: context.selectableItemBackground
        }

        context.theme.obtainStyledAttributes(
            attributeSet,
            R.styleable.LargeActionCard,
            defStyleAttr,
            0
        ).apply {
            try {
                icon = getDrawable(R.styleable.LargeActionCard_icon)
                text = getString(R.styleable.LargeActionCard_text)
                subtext = getString(R.styleable.LargeActionCard_subtext)
                if (hasValue(R.styleable.LargeActionCard_contentColor)) {
                    contentColor = getColor(R.styleable.LargeActionCard_contentColor, 0)
                }
            } finally {
                recycle()
            }
        }

        minimumHeight = context.getPixels(R.dimen.large_action_card_min_height)
        radius = context.getPixels(R.dimen.large_action_card_radius).toFloat()
        elevation = context.getPixels(R.dimen.large_action_card_elevation).toFloat()
        setCardBackgroundColor(context.resolveThemedColor(com.google.android.material.R.attr.colorSurface))
    }

    private fun applyIcon() {
        val drawable = iconDrawable ?: run {
            binding.iconView.background = null
            return
        }
        if (contentColorApplied) {
            val tinted = DrawableCompat.wrap(drawable.mutate())
            DrawableCompat.setTintList(tinted, ColorStateList.valueOf(contentColor))
            binding.iconView.background = tinted
        } else {
            binding.iconView.background = drawable
        }
    }
}
