package com.github.kr328.clash.log

object SystemLogcat {
    /**
     * Crash-dump logcat filters (tag:priority).
     * App diagnostics + JVM crash only — no core traffic tags (Go/LwIP/DEBUG).
     * GL-04: raw Mihomo destinations/DNS must never enter this dump.
     *
     * Includes [AndroidRuntime:E] for the crash screen. User-shareable reports
     * must **not** use [dumpCrash] — stack traces can carry Exception.message
     * (e.g. raw HTTP bodies). Use [dumpAppDiagnostics] + event allowlist instead.
     */
    internal val crashLogFilters = arrayOf(
        "AndroidRuntime:E",
        "GetLineVPN:V",
    )

    /**
     * App tag only — source for GL-19 diagnostic reports.
     * No AndroidRuntime: stack frames / Exception.message must not enter share path.
     */
    internal val diagnosticLogFilters = arrayOf(
        "GetLineVPN:V",
    )

    /** Crash screen: empty string when dump fails (legacy consumers). */
    fun dumpCrash(): String = dump(crashLogFilters) ?: ""

    /**
     * Ring-buffer dump of app diagnostics only (`logcat -d` survives process death).
     * @return `null` when `logcat` cannot be executed (blocked exec, etc.) —
     *         distinct from an empty buffer (`""`).
     */
    fun dumpAppDiagnostics(): String? = dump(diagnosticLogFilters)

    private fun dump(filters: Array<String>): String? {
        return try {
            // -v threadtime: stable timestamp + tag form for the report builder.
            // Brief (`I/GetLineVPN( 1234): …`) is still accepted if a device ignores -v.
            val command = arrayOf("logcat", "-d", "-v", "threadtime", "-s") + filters
            val process = Runtime.getRuntime().exec(command)

            val result = process.inputStream.use { stream ->
                stream.reader().readLines()
                    .filterNot { it.startsWith("------") }
                    .joinToString("\n")
            }

            process.waitFor()

            result.trim()
        } catch (_: Exception) {
            null
        }
    }
}
