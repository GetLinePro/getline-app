package pro.getline.vpn.diagnostics

/**
 * Builds a user-shareable diagnostic report (GL-19).
 *
 * Safety is fail-closed at the builder:
 * - only [SystemLogcat.dumpAppDiagnostics] input (GetLineVPN tag; no AndroidRuntime)
 * - only allowlisted event-name prefixes
 * - redaction is insurance; secrets must not be logged at the source
 *
 * Timestamps from logcat lines are preserved so event order stays readable.
 */
object DiagnosticReportBuilder {
    /**
     * First token of the app log message (after the tag).
     * Keep in sync with intentional `Log.*("event …")` call sites.
     */
    internal val allowedEventPrefixes: List<String> = listOf(
        "session_established",
        "pre_session_auth_failed",
        "post_session_subscription_failed",
        "browser_auth_launch",
        "browser_auth_invalid_callback",
        "auth_tab_result",
        "import_terminal",
        "import_waiter_cancelled",
        "trial_provision",
        "subscription_ui",
        // Authenticated subscriptions 401 recovery; fixed outcomes/status only.
        "session_recovery",
        "session_refresh",
        // Slice 1: startup decision + repair ladder (daily double-login / Home state).
        "startup_route",
        "repair_outcome",
        // Durable replacement cleanup; fixed outcome/stop tokens, never UUIDs.
        "profile_cleanup",
        // Slice 2: VPN connect chain (permission / start / observed core state).
        "vpn_ui",
        "vpn_start",
        "vpn_state",
        // Import boundary: stages/kinds plus a bounded exception message on failure
        // (needed to tell a rejected value from a network fault). Never raw fetch
        // args or profile data; URL/token redaction below is the backstop.
        "profile_import",
        // Same boundary for the paths that carry no progress reporting of their
        // own (subscription refresh, managed delete): failure line only.
        "profile_backend",
    )

    private val uuidRegex = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
    )
    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val emailRegex = Regex(
        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
    )
    private val jwtLikeRegex = Regex(
        "\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b",
    )
    /** Only explicit secret carriers — not bare long identifiers (event names are long). */
    private val bearerTokenRegex = Regex(
        "\\bBearer\\s+[A-Za-z0-9._\\-+/=]+",
        RegexOption.IGNORE_CASE,
    )
    private val basicAuthRegex = Regex(
        "\\bBasic\\s+[A-Za-z0-9+/=_-]+",
        RegexOption.IGNORE_CASE,
    )
    /**
     * `key=value` secrets: bare names, compound `*_token` / known keys (`device_key`),
     * and `authorization=…`. Word-boundary alone misses `auth_token` (`token` after `_`).
     */
    private val assignedSecretRegex = Regex(
        "\\b(?:[A-Za-z0-9_]*?(?:token|secret|password)|device_key|api_key|private_key|authorization)" +
            "\\s*=\\s*\\S+",
        RegexOption.IGNORE_CASE,
    )
    /**
     * HTTP-style colon secrets:
     * - `Authorization: Bearer|Basic|Digest <cred>` (scheme + one credential token)
     * - `Authorization: <opaque>` (single token only — do not swallow `code=401`)
     */
    private val headerSecretRegex = Regex(
        "\\b(?:Authorization|Proxy-Authorization)\\s*:\\s*" +
            "(?:(?:Bearer|Basic|Digest)\\s+\\S+|\\S+)",
        RegexOption.IGNORE_CASE,
    )

    data class Header(
        val versionName: String,
        val versionCode: Long,
        val channel: String,
        val sdkInt: Int,
        val manufacturer: String,
        val model: String,
        val hasSession: Boolean,
        val generatedAt: String,
    )

    /**
     * @param rawLogcat logcat dump, or `null` when [SystemLogcat.dumpAppDiagnostics] failed
     *        (exec blocked, etc.). Empty string means dump succeeded with no lines.
     */
    fun build(header: Header, rawLogcat: String?): String {
        return buildString {
            appendLine("GetLine VPN diagnostic report")
            appendLine("generated_at=${header.generatedAt}")
            appendLine("version=${header.versionName} (${header.versionCode})")
            appendLine("channel=${header.channel}")
            appendLine("android_sdk=${header.sdkInt}")
            appendLine("device=${header.manufacturer} ${header.model}".trim())
            appendLine("has_session=${header.hasSession}")
            appendLine("---")
            if (rawLogcat == null) {
                appendLine("(log buffer dump failed)")
            } else {
                val body = selectEventLines(rawLogcat)
                if (body.isEmpty()) {
                    appendLine("(no allowlisted diagnostic events in log buffer)")
                } else {
                    body.forEach { appendLine(it) }
                }
            }
        }.trimEnd() + "\n"
    }

    /**
     * Keep logcat timestamp prefixes; drop non-allowlisted messages; redact residual secrets.
     */
    fun selectEventLines(rawLogcat: String): List<String> {
        if (rawLogcat.isBlank()) return emptyList()
        return rawLogcat.lineSequence()
            .mapNotNull { line -> processLine(line) }
            .toList()
    }

    internal fun processLine(line: String): String? {
        val trimmed = line.trimEnd()
        if (trimmed.isBlank() || trimmed.startsWith("------")) return null

        val message = extractMessage(trimmed) ?: return null
        if (looksLikeStackOrExceptionNoise(message)) return null
        if (!isAllowlisted(message)) return null

        val redacted = redact(message)
        return replaceMessage(trimmed, message, redacted)
    }

    internal fun isAllowlisted(message: String): Boolean {
        val event = message.trim().substringBefore(' ')
        if (event.isEmpty()) return false
        return allowedEventPrefixes.any { prefix ->
            event == prefix || message.startsWith("$prefix ") || message == prefix
        }
    }

    internal fun redact(message: String): String {
        var out = message
        out = uuidRegex.replace(out, "<uuid>")
        out = urlRegex.replace(out, "<url>")
        out = emailRegex.replace(out, "<email>")
        out = jwtLikeRegex.replace(out, "<token>")
        out = bearerTokenRegex.replace(out, "Bearer <token>")
        out = basicAuthRegex.replace(out, "Basic <token>")
        out = headerSecretRegex.replace(out) { match ->
            val name = match.value.substringBefore(':').trim()
            "$name: <redacted>"
        }
        out = assignedSecretRegex.replace(out) { match ->
            val key = match.value.substringBefore('=').trimEnd()
            "$key=<redacted>"
        }
        return out
    }

    /**
     * Message after the GetLineVPN tag for common logcat formats:
     * - threadtime: `MM-DD HH:MM:SS.mmm  pid  tid I GetLineVPN: message`
     * - brief: `I/GetLineVPN(pid): message` or padded `I/GetLineVPN( 1234): message`
     * - tag: `I/GetLineVPN: message`
     *
     * Prefer matching the parenthesized brief form first: a naive `GetLineVPN:` match
     * can still work, but brief must accept whitespace-padded PIDs from real devices.
     */
    internal fun extractMessage(line: String): String? {
        // Brief / threadtime-with-pid: allow spaces inside parentheses (logcat pads PIDs).
        val brief = Regex("""\bGetLineVPN\s*\(\s*\d+\s*\)\s*:\s*(.*)$""")
        brief.find(line)?.groupValues?.getOrNull(1)?.let { return it }

        val tagColon = Regex("""\bGetLineVPN\s*:\s*(.*)$""")
        return tagColon.find(line)?.groupValues?.getOrNull(1)
    }

    private fun replaceMessage(line: String, originalMessage: String, newMessage: String): String {
        val idx = line.lastIndexOf(originalMessage)
        if (idx < 0) return newMessage
        return line.substring(0, idx) + newMessage
    }

    private fun looksLikeStackOrExceptionNoise(message: String): Boolean {
        val m = message.trimStart()
        if (m.startsWith("at ")) return true
        if (m.startsWith("Caused by:")) return true
        if (m.startsWith("Suppressed:")) return true
        if (m.startsWith("... ") && m.contains(" more")) return true
        // Bare exception class lines without an event prefix.
        if (m.contains("Exception:") || m.contains("Error:")) {
            val event = m.substringBefore(' ')
            if (allowedEventPrefixes.none { event == it || m.startsWith("$it ") }) {
                return true
            }
        }
        return false
    }
}
