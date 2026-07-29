package pro.getline.vpn.getlineui.util

import java.text.SimpleDateFormat
import java.util.Date

fun Long.toBytesString(): String {
    return when {
        this > 1024.0 * 1024 * 1024 * 1024 * 1024 * 1024 ->
            String.format("%.2f EiB", (this.toDouble() / 1024 / 1024 / 1024 / 1024 / 1024 / 1024))
        this > 1024.0 * 1024 * 1024 * 1024 * 1024 ->
            String.format("%.2f PiB", (this.toDouble() / 1024 / 1024 / 1024 / 1024 / 1024))
        this > 1024.0 * 1024 * 1024 * 1024 ->
            String.format("%.2f TiB", (this.toDouble() / 1024 / 1024 / 1024 / 1024))
        this > 1024 * 1024 * 1024 ->
            String.format("%.2f GiB", (this.toDouble() / 1024 / 1024 / 1024))
        this > 1024 * 1024 ->
            String.format("%.2f MiB", (this.toDouble() / 1024 / 1024))
        this > 1024 ->
            String.format("%.2f KiB", (this.toDouble() / 1024))
        else ->
            "$this Bytes"
    }
}

fun Long.toDateStr(): String {
    val simpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
    return simpleDateFormat.format(Date(this))
}
