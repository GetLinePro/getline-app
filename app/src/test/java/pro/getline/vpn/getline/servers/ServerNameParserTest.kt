package pro.getline.vpn.getline.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerNameParserTest {
    @Test
    fun parsesFlagCountryAndVariant() {
        val parsed = ServerNameParser.parse("🇩🇪 Германия | xhttp")

        assertEquals("DE", parsed.countryCode)
        assertEquals("Германия", parsed.groupLabel)
        assertEquals("xhttp", parsed.variantLabel)
    }

    @Test
    fun keepsMultiWordVariantVerbatim() {
        // "Обход" semantics are undocumented — the label must survive unmapped.
        val parsed = ServerNameParser.parse("🇳🇱 Нидерланды | Обход hy2")

        assertEquals("NL", parsed.countryCode)
        assertEquals("Нидерланды", parsed.groupLabel)
        assertEquals("Обход hy2", parsed.variantLabel)
    }

    @Test
    fun rawNameIsPreservedForPatchSelector() {
        val raw = "🇩🇪 Германия | Обход vless"

        assertEquals(raw, ServerNameParser.parse(raw).rawName)
    }

    @Test
    fun autoEntryHasNoCountryAndStaysUngrouped() {
        val parsed = ServerNameParser.parse("⚡ Авто")

        assertNull(parsed.countryCode)
        assertTrue(parsed.isUngrouped)
        assertEquals("⚡ Авто", parsed.groupLabel)
    }

    @Test
    fun nameWithoutFlagIsUngroupedButKept() {
        val parsed = ServerNameParser.parse("Германия | hy2")

        assertNull(parsed.countryCode)
        assertTrue(parsed.isUngrouped)
        assertEquals("Германия", parsed.groupLabel)
        assertEquals("hy2", parsed.variantLabel)
    }

    @Test
    fun flagWithoutVariantParses() {
        val parsed = ServerNameParser.parse("🇩🇪 Германия")

        assertEquals("DE", parsed.countryCode)
        assertEquals("Германия", parsed.groupLabel)
        assertNull(parsed.variantLabel)
    }

    @Test
    fun sameCountryDifferentVariantsShareCountryCode() {
        val names = listOf(
            "🇳🇱 Нидерланды | Обход",
            "🇳🇱 Нидерланды | hy2",
            "🇳🇱 Нидерланды | vless",
        )

        val codes = names.map { ServerNameParser.parse(it).countryCode }.distinct()

        assertEquals(listOf("NL"), codes)
    }

    @Test
    fun emptyNameIsKeptAndUngrouped() {
        val parsed = ServerNameParser.parse("")

        assertNull(parsed.countryCode)
        assertTrue(parsed.isUngrouped)
    }

    @Test
    fun blankVariantAfterSeparatorBecomesNull() {
        val parsed = ServerNameParser.parse("🇩🇪 Германия |  ")

        assertEquals("DE", parsed.countryCode)
        assertNull(parsed.variantLabel)
    }

    @Test
    fun flagWithNoLabelStaysUngrouped() {
        val parsed = ServerNameParser.parse("🇩🇪 | xhttp")

        assertNull(parsed.countryCode)
        assertTrue(parsed.isUngrouped)
    }

    @Test
    fun nonFlagEmojiIsNotACountry() {
        assertNull(ServerNameParser.countryCodeOf("⚡ Авто"))
        assertNull(ServerNameParser.countryCodeOf("🚀 Fast"))
    }

    @Test
    fun singleRegionalIndicatorIsNotACountry() {
        assertNull(ServerNameParser.countryCodeOf("🇩 Германия"))
    }

    @Test
    fun surrogatePairsAreReadAsCodePoints() {
        // Guards the Char-vs-codePoint trap: each indicator is a surrogate pair.
        assertEquals("DE", ServerNameParser.countryCodeOf("🇩🇪"))
        assertEquals("NL", ServerNameParser.countryCodeOf("🇳🇱"))
        assertEquals("AA", ServerNameParser.countryCodeOf("🇦🇦"))
        assertEquals("ZZ", ServerNameParser.countryCodeOf("🇿🇿"))
    }
}
