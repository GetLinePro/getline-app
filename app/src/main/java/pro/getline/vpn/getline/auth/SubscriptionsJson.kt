package pro.getline.vpn.getline.auth

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Pure JSON mapping for GET /api/subscriptions. Kept separate for unit tests.
 */
object SubscriptionsJson {
    fun parseResponse(json: JSONObject): SubscriptionsResponse {
        val items = json.optJSONArray("subscriptions") ?: JSONArray()
        val subscriptions = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(parseItem(item))
            }
        }
        return SubscriptionsResponse(
            autopayAvailable = json.optBoolean("autopay_available", false),
            subscriptions = subscriptions,
        )
    }

    fun parseItem(item: JSONObject): SubscriptionItem {
        val trafficObj = item.optJSONObject("traffic")
        return SubscriptionItem(
            id = item.optIdAsString("id"),
            name = item.optStringOrNull("name"),
            planName = item.optStringOrNull("plan_name"),
            planType = item.optStringOrNull("plan_type"),
            kind = item.optStringOrNull("kind"),
            isPrimary = item.optBoolean("is_primary", false) ||
                item.optBoolean("primary", false),
            isActive = item.optBoolean("is_active", false),
            expireAtEpochMillis = item.optExpireAtMillis("expire_at"),
            daysLeft = item.optIntOrNull("days_left"),
            deviceLimit = item.optIntOrNull("device_limit"),
            totalDeviceLimit = item.optIntOrNull("total_device_limit"),
            devicesCount = item.optIntOrNull("devices_count"),
            traffic = trafficObj?.let { parseTraffic(it) },
            autopayEnabled = item.optBoolean("autopay_enabled", false),
            renewalDisabled = item.optBoolean("renewal_disabled", false),
            planArchived = item.optBoolean("plan_archived", false),
            subscriptionLink = item.optStringOrNull("subscription_link")
                ?: item.optStringOrNull("subscription_url")
                ?: item.optStringOrNull("url")
                ?: item.optStringOrNull("link"),
        )
    }

    fun parseTraffic(obj: JSONObject): SubscriptionTraffic {
        return SubscriptionTraffic(
            usedBytes = obj.optLong("used_bytes", 0L),
            limitBytes = obj.optLong("limit_bytes", 0L),
            usedPercent = obj.optDouble("used_percent", 0.0),
            isUnlimited = obj.optBoolean("is_unlimited", false),
        )
    }

    private fun JSONObject.optIdAsString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> value.toLong().toString()
            else -> value.toString().takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)?.toString() ?: return null
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return try {
            getInt(key)
        } catch (_: Exception) {
            optStringOrNull(key)?.toIntOrNull()
        }
    }

    private fun JSONObject.optExpireAtMillis(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return when (val value = opt(key)) {
            null, JSONObject.NULL -> null
            is Number -> numberToEpochMillis(value.toLong())
            else -> parseDateTimeMillis(value.toString())
        }
    }

    /**
     * Heuristic: values below 1e12 are treated as unix seconds; otherwise millis.
     */
    internal fun numberToEpochMillis(value: Long): Long {
        return if (value in 1 until 1_000_000_000_000L) value * 1000L else value
    }

    internal fun parseDateTimeMillis(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null") return null
        trimmed.toLongOrNull()?.let { return numberToEpochMillis(it) }

        // ISO-8601 offsets: XXX = +HH:mm (and Z), XX = +HHmm, X = +HH.
        // Prefer longer offset patterns first. parse(String) alone can accept
        // trailing text (e.g. X stops at +03 in +03:30); require full consumption.
        val isoPatterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ssXX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
        for (pattern in isoPatterns) {
            try {
                val format = SimpleDateFormat(pattern, Locale.US).apply {
                    isLenient = false
                    if (pattern.contains('X')) {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                }
                val candidate = if (pattern.contains("SSS") && trimmed.contains('.')) {
                    normalizeFractionalSeconds(trimmed)
                } else {
                    trimmed
                }
                val position = ParsePosition(0)
                val date = format.parse(candidate, position) ?: continue
                if (position.index == candidate.length) {
                    return date.time
                }
            } catch (_: IllegalArgumentException) {
                // try next
            }
        }
        return null
    }

    /**
     * Truncates fractional seconds to millis while preserving timezone suffix.
     *
     * Examples:
     * - `...59.123456Z` → `...59.123Z`
     * - `...59.123456+03:00` → `...59.123+03:00`
     * - `...59.1-05:00` → `...59.100-05:00`
     */
    internal fun normalizeFractionalSeconds(raw: String): String {
        val suffixStart = findTimezoneSuffixStart(raw) ?: raw.length
        val dot = raw.indexOf('.')
        if (dot < 0 || dot >= suffixStart) return raw
        val fracDigits = raw.substring(dot + 1, suffixStart).takeWhile { it.isDigit() }
        if (fracDigits.isEmpty()) return raw
        val millis = fracDigits.padEnd(3, '0').take(3)
        val suffix = raw.substring(suffixStart)
        return raw.substring(0, dot + 1) + millis + suffix
    }

    /**
     * Index of `Z` or numeric offset (`+hh:mm` / `-hh:mm` / `+hhmm`) after the
     * time component. Date separators before `T` are ignored.
     */
    internal fun findTimezoneSuffixStart(raw: String): Int? {
        val z = raw.indexOf('Z')
        if (z > 0) return z

        val t = raw.indexOf('T')
        if (t < 0) return null

        for (i in (t + 1) until raw.length) {
            val c = raw[i]
            if ((c == '+' || c == '-') && raw[i - 1].isDigit()) {
                return i
            }
        }
        return null
    }
}
