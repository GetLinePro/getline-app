package pro.getline.vpn.getline.auth

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * RFC 7636 PKCE helpers for native browser OAuth.
 *
 * Pure JVM (no `java.util.Base64` / Android APIs) so minSdk 23 and unit tests
 * share one encoder. Challenge is base64url without padding.
 */
object NativeAuthPkce {
    /** RFC 7636 unreserved: ALPHA / DIGIT / "-" / "." / "_" / "~" */
    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private const val B64URL =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Within the RFC range 43–128; 64 is enough entropy for S256. */
    const val VERIFIER_LENGTH = 64

    data class PkcePair(
        val verifier: String,
        val challenge: String,
    )

    fun generate(random: SecureRandom = SecureRandom()): PkcePair {
        val verifier = buildString(VERIFIER_LENGTH) {
            repeat(VERIFIER_LENGTH) {
                append(UNRESERVED[random.nextInt(UNRESERVED.length)])
            }
        }
        return PkcePair(verifier = verifier, challenge = challengeS256(verifier))
    }

    fun challengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPadding(digest)
    }

    /** Base64url without `=` padding (RFC 7636 / RFC 4648 §5). */
    internal fun base64UrlNoPadding(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                (data[i + 2].toInt() and 0xff)
            out.append(B64URL[(n shr 18) and 63])
            out.append(B64URL[(n shr 12) and 63])
            out.append(B64URL[(n shr 6) and 63])
            out.append(B64URL[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                out.append(B64URL[(n shr 18) and 63])
                out.append(B64URL[(n shr 12) and 63])
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or
                    ((data[i + 1].toInt() and 0xff) shl 8)
                out.append(B64URL[(n shr 18) and 63])
                out.append(B64URL[(n shr 12) and 63])
                out.append(B64URL[(n shr 6) and 63])
            }
        }
        return out.toString()
    }
}
