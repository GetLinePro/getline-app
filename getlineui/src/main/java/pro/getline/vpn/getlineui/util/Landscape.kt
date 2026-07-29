package pro.getline.vpn.getlineui.util

import android.content.Context
import pro.getline.vpn.getlineui.R
import pro.getline.vpn.getlineui.ui.Insets

fun Insets.landscape(context: Context): Insets {
    val displayMetrics = context.resources.displayMetrics
    val minWidth = context.getPixels(R.dimen.surface_landscape_min_width)

    val width = displayMetrics.widthPixels
    val height = displayMetrics.heightPixels

    return if (width > height && width > minWidth) {
        val expectedWidth = width.coerceAtMost(height.coerceAtLeast(minWidth))

        val padding = (width - expectedWidth).coerceAtLeast(start + end) / 2

        copy(start = padding.coerceAtLeast(start), end = padding.coerceAtLeast(end))
    } else {
        this
    }
}
