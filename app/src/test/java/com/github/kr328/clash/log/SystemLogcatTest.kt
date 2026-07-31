package com.github.kr328.clash.log

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SystemLogcatTest {
    @Test
    fun crashDumpWhitelist_isMinimalAppSafeTags() {
        assertArrayEquals(
            arrayOf("AndroidRuntime:E", "GetLineVPN:V"),
            SystemLogcat.crashLogFilters,
        )
    }

    @Test
    fun crashDumpWhitelist_excludesCoreTrafficTags() {
        val filters = SystemLogcat.crashLogFilters.toList()
        for (forbidden in listOf("Go", "LwIP", "DEBUG", "Go:V", "LwIP:V", "DEBUG:V")) {
            assertFalse(
                "crash dump must not include core/legacy tag filter: $forbidden",
                filters.any { it == forbidden || it.startsWith("$forbidden:") },
            )
        }
    }
}
