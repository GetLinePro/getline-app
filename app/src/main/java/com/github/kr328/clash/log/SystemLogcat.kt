package com.github.kr328.clash.log

object SystemLogcat {
    /**
     * Crash-dump logcat filters (tag:priority).
     * App diagnostics + JVM crash only — no core traffic tags (Go/LwIP/DEBUG).
     * GL-04: raw Mihomo destinations/DNS must never enter this dump.
     */
    internal val crashLogFilters = arrayOf(
        "AndroidRuntime:E",
        "GetLineVPN:V",
    )

    private val command = arrayOf(
        "logcat",
        "-d",
        "-s",
    ) + crashLogFilters

    fun dumpCrash(): String {
        return try {
            val process = Runtime.getRuntime().exec(command)

            val result = process.inputStream.use { stream ->
                stream.reader().readLines()
                    .filterNot { it.startsWith("------") }
                    .joinToString("\n")
            }

            process.waitFor()

            result.trim()
        } catch (e: Exception) {
            ""
        }
    }
}
