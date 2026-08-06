package pro.getline.vpn.getline.auth

/**
 * One in-flight native browser OAuth attempt.
 *
 * [verifier] is the only secret that must survive process death until exchange.
 * The one-time `code` is never stored here.
 *
 * [callbackUri] is the expected redirect base (`scheme:/oauth2redirect`) without
 * query parameters — used to reject stray deep links.
 */
data class PendingNativeAuth(
    val provider: String,
    val verifier: String,
    val callbackUri: String,
    val createdAtMs: Long,
    val correlationId: String,
) {
    companion object {
        /** Wall-clock TTL for the whole attempt (browser open → exchange). */
        const val TTL_MS: Long = 10 * 60 * 1000L
    }

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - createdAtMs > TTL_MS
}
