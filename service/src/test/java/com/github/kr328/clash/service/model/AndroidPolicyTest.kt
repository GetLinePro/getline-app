package com.github.kr328.clash.service.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidPolicyTest {
    @Test
    fun parse_emptyArray_isEmpty() {
        val policy = AndroidPolicy.parse("""{"version":1,"excludedPackages":[]}""")

        assertEquals(AndroidPolicy.EMPTY, policy)
        assertTrue(policy.excludedPackages.isEmpty())
    }

    @Test
    fun parse_trimsAndDeduplicates() {
        val policy = AndroidPolicy.parse(
            """{"version":1,"excludedPackages":["  com.example.one ","com.example.two","com.example.one"]}""",
        )

        assertEquals(setOf("com.example.one", "com.example.two"), policy.excludedPackages)
    }

    @Test
    fun parse_unknownVersion_fails() {
        assertFails("unsupported version") {
            AndroidPolicy.parse("""{"version":2,"excludedPackages":[]}""")
        }
    }

    @Test
    fun parse_malformedJson_fails() {
        assertFails("") {
            AndroidPolicy.parse("{")
        }
    }

    @Test
    fun parse_missingVersion_fails() {
        assertFails("missing version") {
            AndroidPolicy.parse("""{"excludedPackages":[]}""")
        }
    }

    @Test
    fun parse_missingPackages_fails() {
        assertFails("missing excludedPackages") {
            AndroidPolicy.parse("""{"version":1}""")
        }
    }

    @Test
    fun parse_nullPackages_fails() {
        assertFails("missing excludedPackages") {
            AndroidPolicy.parse("""{"version":1,"excludedPackages":null}""")
        }
    }

    @Test
    fun parse_packagesObject_fails() {
        assertFails("must be an array") {
            AndroidPolicy.parse("""{"version":1,"excludedPackages":{}}""")
        }
    }

    @Test
    fun parse_nonStringEntry_fails() {
        assertFails("must be a string") {
            AndroidPolicy.parse("""{"version":1,"excludedPackages":[1]}""")
        }
    }

    @Test
    fun parse_emptyEntry_fails() {
        assertFails("is empty") {
            AndroidPolicy.parse("""{"version":1,"excludedPackages":["  "]}""")
        }
    }

    @Test
    fun parse_nullEntry_fails() {
        assertFails("is null") {
            AndroidPolicy.parse("""{"version":1,"excludedPackages":[null]}""")
        }
    }

    private fun assertFails(needle: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            if (needle.isNotEmpty()) {
                assertTrue(error.message, error.message?.contains(needle) == true)
            }
            return
        }
        throw AssertionError("expected failure")
    }
}
