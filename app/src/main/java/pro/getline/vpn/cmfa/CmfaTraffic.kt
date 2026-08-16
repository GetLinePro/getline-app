package pro.getline.vpn.cmfa

import pro.getline.vpn.getlineui.model.GetLineTraffic

/**
 * Decode the packed traffic counter the CMFA JNI bridge hands back.
 *
 * `down_scale_traffic` (core/src/main/cpp/bridge_helper.c) packs each half as
 * two type bits plus a 30-bit figure. The figure is a plain byte count only for
 * type 0 (below 1 KiB); every other type carries **hundredths** of the unit it
 * names — hundredths of a KiB, MiB or GiB.
 *
 * core/util/Traffic.kt never undoes those hundredths. It multiplies the figure
 * back up by the unit and then divides the printed number by 100 again, so its
 * strings come out right while the number it passes around is a hundred times a
 * byte count. Product code must not inherit that: [GetLineTraffic] is declared
 * in bytes, so this decode returns bytes and any byte formatter is then simply
 * correct.
 *
 * Precision is the encoder's, not ours — it quantises to 1/100 of the unit, so
 * a GiB-scale figure is exact to about 10 MB. Nothing here recovers that.
 */
internal fun unpackTraffic(packed: Long): GetLineTraffic {
    return GetLineTraffic(
        uploadedBytes = decodeHalfToBytes(packed ushr 32),
        downloadedBytes = decodeHalfToBytes(packed and 0xFFFFFFFF),
    )
}

private fun decodeHalfToBytes(value: Long): Long {
    val type = (value ushr 30) and 0x3
    val figure = value and 0x3FFFFFFF

    return when (type) {
        0L -> figure
        1L -> figure * 1024L / 100L
        2L -> figure * 1024L * 1024L / 100L
        3L -> figure * 1024L * 1024L * 1024L / 100L
        // Two bits, four cases: unreachable, but `when` over a Long needs it.
        else -> throw IllegalArgumentException("invalid traffic type $type")
    }
}
