package pro.getline.vpn.getline.auth

import java.util.Locale
import java.util.concurrent.TimeUnit
import pro.getline.vpn.getlineui.R as GetLineUiR

/**
 * Display rules for `X-GetLine-Tag` / `X-GetLine-Status`.
 *
 * Stored values are the trimmed header text. This layer decides what the card
 * may show: normalize, reject junk, map known codes, leave unknown visible.
 */
object SubscriptionHeaderDisplay {
    private const val MAX_LEN = 16
    private val ALLOWED = Regex("^[A-Z0-9_]+$")

    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val upper = trimmed.uppercase(Locale.US)
        if (upper.length > MAX_LEN || !ALLOWED.matches(upper)) return null
        return upper
    }

    fun tariffTitle(code: String, string: (Int) -> String): String {
        val res = when (code) {
            "PAID" -> GetLineUiR.string.get_line_tariff_paid
            "LTEPLUS" -> GetLineUiR.string.get_line_tariff_lteplus
            "PAIDPLUS" -> GetLineUiR.string.get_line_tariff_paidplus
            "UNLIM" -> GetLineUiR.string.get_line_tariff_unlim
            "STUDENT" -> GetLineUiR.string.get_line_tariff_student
            "LTE" -> GetLineUiR.string.get_line_tariff_lte
            "FRIENDS" -> GetLineUiR.string.get_line_tariff_friends
            else -> null
        }
        return res?.let(string) ?: code
    }

    fun statusText(code: String, string: (Int) -> String): String {
        return if (code == "ACTIVE") {
            string(GetLineUiR.string.get_line_home_status_active)
        } else {
            code
        }
    }

    fun isActiveStatus(code: String?): Boolean = code == "ACTIVE"

    fun daysLeft(expireAtEpochMillis: Long?, nowMillis: Long): Int? {
        if (expireAtEpochMillis == null || expireAtEpochMillis <= 0L) return null
        val days = TimeUnit.MILLISECONDS.toDays(expireAtEpochMillis - nowMillis)
        return days.toInt().coerceAtLeast(0)
    }
}
