package pro.getline.vpn.cmfa

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The invariant under test is the model's own claim: after [unpackTraffic], a
 * field named `…Bytes` holds bytes.
 *
 * The bridge does not send bytes. It sends a unit type plus hundredths of that
 * unit, and the formatter this code replaced happened to divide by a hundred
 * again, so a wrong number and a wrong formatter printed a right string. Anyone
 * writing a normal byte formatter over the old model got a figure a hundred
 * times too large with nothing to warn them. These cases exist so that trade
 * cannot be made again silently.
 *
 * [packHalf] mirrors `down_scale_traffic` in core/src/main/cpp/bridge_helper.c
 * byte for byte, including its `1042 * 1024 * 1024` threshold — that literal is
 * upstream's typo for 1024, and it is reproduced rather than corrected so the
 * tests describe what actually arrives.
 */
class CmfaTrafficTest {
    @Test
    fun belowOneKib_staysAnExactByteCount() {
        // Type 0 is the one case the bridge sends unscaled.
        val traffic = unpackTraffic(pack(upload = 0L, download = 999L))

        assertEquals(999L, traffic.downloadedBytes)
        assertEquals(999L, traffic.totalBytes)
    }

    @Test
    fun justAboveOneKib_decodesToBytes_notToHundredthsOfAKib() {
        val bytes = 1500L

        val traffic = unpackTraffic(pack(upload = 0L, download = bytes))

        assertWithinEncoderPrecision(bytes, traffic.downloadedBytes, unit = KIB)
    }

    @Test
    fun screenshotRegression_oneHundredSixtySixKib() {
        // 170_916 B is what the ring rendered as "166,91 KiB" before this change.
        val bytes = 170_916L

        val traffic = unpackTraffic(pack(upload = 0L, download = bytes))

        assertWithinEncoderPrecision(bytes, traffic.downloadedBytes, unit = KIB)
        // The old model would have carried 17_091_584 here — a byte formatter
        // over it printed 16 MB for a 167 kB session.
        assertTrue(
            "decoded ${traffic.downloadedBytes} is the hundredths figure, not bytes",
            traffic.downloadedBytes < 1L * MIB,
        )
    }

    @Test
    fun kibToMibBoundary_holdsOnBothSides() {
        val justUnder = MIB - 1L
        val justOver = MIB + 1L

        assertWithinEncoderPrecision(
            justUnder,
            unpackTraffic(pack(upload = 0L, download = justUnder)).downloadedBytes,
            unit = KIB,
        )
        assertWithinEncoderPrecision(
            justOver,
            unpackTraffic(pack(upload = 0L, download = justOver)).downloadedBytes,
            unit = MIB,
        )
    }

    @Test
    fun mibToGibBoundary_holdsAcrossTheUpstreamTypoThreshold() {
        // Between 1024 and 1042 MiB the bridge still sends type 2. The decode
        // must not assume the type follows the magnitude.
        val insideTheGap = 1030L * MIB
        val past = 2L * GIB

        assertWithinEncoderPrecision(
            insideTheGap,
            unpackTraffic(pack(upload = 0L, download = insideTheGap)).downloadedBytes,
            unit = MIB,
        )
        assertWithinEncoderPrecision(
            past,
            unpackTraffic(pack(upload = 0L, download = past)).downloadedBytes,
            unit = GIB,
        )
    }

    @Test
    fun uploadAndDownload_areDecodedIndependently_andTotalIsTheirSum() {
        val upload = 3L * MIB
        val download = 700L * MIB

        val traffic = unpackTraffic(pack(upload = upload, download = download))

        assertWithinEncoderPrecision(upload, traffic.uploadedBytes, unit = MIB)
        assertWithinEncoderPrecision(download, traffic.downloadedBytes, unit = MIB)
        assertEquals(
            traffic.uploadedBytes + traffic.downloadedBytes,
            traffic.totalBytes,
        )
    }

    @Test
    fun halvesOfDifferentTypes_doNotContaminateEachOther() {
        // A fresh tunnel: a few hundred bytes up (type 0), megabytes down.
        val upload = 400L
        val download = 12L * MIB

        val traffic = unpackTraffic(pack(upload = upload, download = download))

        assertEquals(upload, traffic.uploadedBytes)
        assertWithinEncoderPrecision(download, traffic.downloadedBytes, unit = MIB)
    }

    /**
     * The encoder throws away everything below 1/100 of the unit it chose, so
     * that step is the tightest any decode can be. Asserting against it — rather
     * than against a fixed byte count — is what makes these tests fail on a
     * scale error and pass on rounding.
     */
    private fun assertWithinEncoderPrecision(expected: Long, actual: Long, unit: Long) {
        val step = unit / 100L + 1L

        assertTrue(
            "expected ~$expected bytes, got $actual (tolerance $step)",
            abs(actual - expected) <= step,
        )
    }

    private fun pack(upload: Long, download: Long): Long =
        (packHalf(upload) shl 32) or packHalf(download)

    private fun packHalf(bytes: Long): Long = when {
        bytes > 1042L * 1024 * 1024 ->
            ((bytes * 100 / 1024 / 1024 / 1024) and MASK) or (3L shl 30)
        bytes > 1024L * 1024 ->
            ((bytes * 100 / 1024 / 1024) and MASK) or (2L shl 30)
        bytes > 1024L ->
            ((bytes * 100 / 1024) and MASK) or (1L shl 30)
        else -> bytes and MASK
    }

    private companion object {
        const val MASK = 0x3FFFFFFFL
        const val KIB = 1024L
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * 1024L * 1024L
    }
}
