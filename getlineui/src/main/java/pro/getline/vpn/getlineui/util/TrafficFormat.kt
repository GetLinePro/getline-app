package pro.getline.vpn.getlineui.util

import android.content.Context
import android.text.format.Formatter
import pro.getline.vpn.getlineui.model.GetLineTraffic

/**
 * Session traffic for the connect ring.
 *
 * [GetLineTraffic] holds real bytes (see `unpackTraffic` at the CMFA boundary),
 * so the platform formatter is the whole implementation: it picks the unit and
 * localises it. This used to be a copy of core's `trafficString`, which printed
 * KiB/MiB/GiB out of a figure that was a hundred times a byte count — correct on
 * screen only because both halves of that were wrong.
 */
fun GetLineTraffic.formatTotal(context: Context): String =
    Formatter.formatFileSize(context, totalBytes)
