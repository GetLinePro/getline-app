package pro.getline.vpn.getline.servers

/**
 * One parsed entry from the main Mihomo selector group.
 *
 * [rawName] is the only identity Mihomo understands — always pass it back to
 * patchSelector unchanged. Everything else is presentation derived from it.
 *
 * [variantLabel] is deliberately opaque. Observed values are "xhttp", "hy2",
 * "vless", "Обход", "Обход hy2" — what "Обход" actually routes through is not
 * documented anywhere in this repo, so it is shown verbatim rather than mapped
 * to an invented semantic name. Split it once the meaning is confirmed.
 */
data class ParsedServerName(
    val rawName: String,
    val countryCode: String?,
    val groupLabel: String,
    val variantLabel: String?,
    /** Leading flag emoji, kept so grouped rows do not lose it. */
    val flag: String? = null,
) {
    /** True when the name yielded no country — entry stays ungrouped. */
    val isUngrouped: Boolean
        get() = countryCode == null

    /** Group label with its flag restored, for display. */
    val displayLabel: String
        get() = if (flag == null) groupLabel else "$flag $groupLabel"

    /** Full name minus the flag — disambiguates variants with equal labels. */
    val qualifiedLabel: String
        get() = if (variantLabel == null) groupLabel else "$groupLabel · $variantLabel"

    /** Fully identifying label with flag — names one specific node. */
    val displayQualifiedLabel: String
        get() = if (flag == null) qualifiedLabel else "$flag $qualifiedLabel"
}

/**
 * Splits raw proxy names into a country group and a variant.
 *
 * Never drops an entry: anything that does not parse is returned with its raw
 * name as the label and no country, so it renders as its own row.
 */
object ServerNameParser {
    private const val SEPARATOR = '|'

    /** Regional indicator block — two of these form a flag (e.g. 🇩🇪 -> "DE"). */
    private const val REGIONAL_INDICATOR_FIRST = 0x1F1E6
    private const val REGIONAL_INDICATOR_LAST = 0x1F1FF

    fun parse(rawName: String): ParsedServerName {
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) {
            return ParsedServerName(rawName, null, rawName, null)
        }

        val country = countryCodeOf(trimmed)
        // A flag is two code points, each a UTF-16 surrogate pair.
        val flag = if (country != null) trimmed.take(FLAG_CHAR_LENGTH) else null
        val withoutFlag = if (country != null) {
            trimmed.drop(FLAG_CHAR_LENGTH).trim()
        } else {
            trimmed
        }

        val separator = withoutFlag.indexOf(SEPARATOR)
        val groupLabel: String
        val variantLabel: String?
        if (separator >= 0) {
            groupLabel = withoutFlag.substring(0, separator).trim()
            variantLabel = withoutFlag.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
        } else {
            groupLabel = withoutFlag
            variantLabel = null
        }

        // A flag with no readable label behind it is not a usable group.
        if (groupLabel.isEmpty()) {
            return ParsedServerName(rawName, null, trimmed, variantLabel)
        }

        return ParsedServerName(
            rawName = rawName,
            countryCode = country,
            groupLabel = groupLabel,
            variantLabel = variantLabel,
            flag = flag,
        )
    }

    /**
     * ISO country code from a leading flag emoji, or null.
     * Reads code points — a Char here would only see half of a surrogate pair.
     */
    fun countryCodeOf(name: String): String? {
        if (name.length < FLAG_CHAR_LENGTH) return null

        val first = name.codePointAt(0)
        if (!isRegionalIndicator(first)) return null

        val secondIndex = Character.charCount(first)
        if (secondIndex >= name.length) return null

        val second = name.codePointAt(secondIndex)
        if (!isRegionalIndicator(second)) return null

        // Guard against a flag built from more than two indicators.
        if (Character.charCount(first) + Character.charCount(second) != FLAG_CHAR_LENGTH) {
            return null
        }

        return charArrayOf(letterOf(first), letterOf(second)).concatToString()
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean =
        codePoint in REGIONAL_INDICATOR_FIRST..REGIONAL_INDICATOR_LAST

    private fun letterOf(codePoint: Int): Char =
        ('A' + (codePoint - REGIONAL_INDICATOR_FIRST))

    /** Two regional indicators, each a surrogate pair. */
    private const val FLAG_CHAR_LENGTH = 4
}
