package pro.getline.vpn.getlineui.util

import pro.getline.vpn.getlineui.model.GetLineTraffic

/**
 * Product-facing session traffic label (connect ring).
 *
 * Copied byte-for-byte from core Traffic.trafficString so Home keeps the same
 * on-screen strings (including integer-then-/100 scaling).
 */
fun GetLineTraffic.formatTotal(): String = trafficString(totalBytes)

// Source: core/util/Traffic.kt private trafficString — do not "fix" scaling.
private fun trafficString(scaled: Long): String {
    return when {
        scaled > 1024 * 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024 / 1024

            String.format("%.2f GiB", data.toFloat() / 100)
        }
        scaled > 1024 * 1024 * 100L -> {
            val data = scaled / 1024 / 1024

            String.format("%.2f MiB", data.toFloat() / 100)
        }
        scaled > 1024 * 100L -> {
            val data = scaled / 1024

            String.format("%.2f KiB", data.toFloat() / 100)
        }
        else -> {
            "$scaled Bytes"
        }
    }
}
