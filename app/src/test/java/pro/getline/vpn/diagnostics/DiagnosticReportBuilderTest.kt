package pro.getline.vpn.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportBuilderTest {
    private val header = DiagnosticReportBuilder.Header(
        versionName = "0.1.12",
        versionCode = 2009,
        channel = "prod",
        sdkInt = 34,
        manufacturer = "Xiaomi",
        model = "2201117TY",
        hasSession = true,
        generatedAt = "2026-08-01T12:00:00Z",
    )

    @Test
    fun allowlist_keepsNamedEvents_withTimestamps() {
        val raw = """
            08-01 12:00:01.000  1000  1001 I GetLineVPN: browser_auth_launch method=Google
            08-01 12:00:02.000  1000  1001 W GetLineVPN: auth_tab_result code=0 elapsed_ms=1200 browser=com.android.chrome
            08-01 12:00:03.000  1000  1001 W GetLineVPN: post_session_subscription_failed kind=HttpFailure code=503
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("08-01 12:00:01.000"))
        assertTrue(lines[0].contains("browser_auth_launch method=Google"))
        assertTrue(lines[2].contains("post_session_subscription_failed"))
    }

    @Test
    fun allowlist_dropsUnlistedAppNoise() {
        val raw = """
            08-01 12:00:01.000  1000  1001 I GetLineVPN: App becomes visible
            08-01 12:00:02.000  1000  1001 D GetLineVPN: Process main started
            08-01 12:00:03.000  1000  1001 I GetLineVPN: session_established has_refresh=true binding=true
            08-01 12:00:04.000  1000  1001 D GetLineVPN: fetch action=FetchConfiguration args=https://evil.example/sub progress=1/2
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("session_established"))
        assertFalse(lines.any { it.contains("fetch action") })
        assertFalse(lines.any { it.contains("evil.example") })
    }

    @Test
    fun dropsStackFramesAndExceptionBodiesEvenUnderAppTag() {
        val raw = """
            08-01 12:00:01.000  1000  1001 E GetLineVPN: java.lang.RuntimeException: {"token":"secret-body"}
            08-01 12:00:01.001  1000  1001 E GetLineVPN: 	at pro.getline.vpn.Foo.bar(Foo.kt:1)
            08-01 12:00:01.002  1000  1001 E GetLineVPN: Caused by: java.io.IOException: https://sub.example/x
            08-01 12:00:02.000  1000  1001 W GetLineVPN: pre_session_auth_failed kind=HttpFailure code=401
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("pre_session_auth_failed"))
        assertFalse(lines.any { it.contains("secret-body") })
        assertFalse(lines.any { it.contains("at pro.getline") })
        assertFalse(lines.any { it.contains("sub.example") })
    }

    @Test
    fun redaction_scrubsUuidUrlEmailToken_asInsurance() {
        val msg = "import_terminal success id=550e8400-e29b-41d4-a716-446655440000 " +
            "link=https://sub.getline.pro/abc " +
            "token=rawsecrettokenvalue " +
            "email=user@example.com " +
            "jwt=eyJhbGciOiJIUzI1NiJ9.aaa.bbb " +
            "Bearer supersecrettokenvalue0123456789abcd"
        val redacted = DiagnosticReportBuilder.redact(msg)
        assertFalse(redacted.contains("550e8400"))
        assertFalse(redacted.contains("https://"))
        assertFalse(redacted.contains("user@example.com"))
        assertFalse(redacted.contains("rawsecrettokenvalue"))
        assertFalse(redacted.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(redacted.contains("supersecrettokenvalue0123456789abcd"))
        assertTrue(redacted.contains("<uuid>"))
        assertTrue(redacted.contains("<url>"))
        assertTrue(redacted.contains("<email>"))
        assertTrue(redacted.contains("token=<redacted>"))
        assertTrue(redacted.contains("Bearer <token>"))
        // Event name must survive (do not treat long snake_case as a token).
        assertTrue(redacted.startsWith("import_terminal"))
    }

    @Test
    fun redaction_scrubsCompoundAndColonFormSecrets() {
        val msg = "pre_session_auth_failed kind=Protocol " +
            "auth_token=opaqueAuthTokenValue " +
            "device_key=opaqueDeviceKeyValue " +
            "access_token=opaqueAccess " +
            "Authorization: Basic dXNlcjpwYXNz " +
            "Proxy-Authorization: Bearer headerBearerTokenValue"
        val redacted = DiagnosticReportBuilder.redact(msg)
        assertFalse(redacted.contains("opaqueAuthTokenValue"))
        assertFalse(redacted.contains("opaqueDeviceKeyValue"))
        assertFalse(redacted.contains("opaqueAccess"))
        assertFalse(redacted.contains("dXNlcjpwYXNz"))
        assertFalse(redacted.contains("headerBearerTokenValue"))
        assertTrue(redacted.contains("auth_token=<redacted>"))
        assertTrue(redacted.contains("device_key=<redacted>"))
        assertTrue(redacted.contains("access_token=<redacted>"))
        assertTrue(redacted.contains("Authorization: <redacted>"))
        assertTrue(redacted.contains("Proxy-Authorization: <redacted>"))
        assertTrue(redacted.startsWith("pre_session_auth_failed"))
    }

    @Test
    fun redaction_headerColon_preservesTrailingSignalFields() {
        val msg = "pre_session_auth_failed kind=HttpFailure " +
            "Authorization: abc123secret code=401"
        val redacted = DiagnosticReportBuilder.redact(msg)
        assertFalse(redacted.contains("abc123secret"))
        assertTrue(redacted.contains("Authorization: <redacted>"))
        assertTrue(redacted.contains("code=401"))
    }

    @Test
    fun extractMessage_acceptsBriefFormatWithPaddedPid() {
        val line = "I/GetLineVPN( 1234): session_established has_refresh=true binding=true"
        val message = DiagnosticReportBuilder.extractMessage(line)
        assertEquals("session_established has_refresh=true binding=true", message)

        val tight = "W/GetLineVPN(99): pre_session_auth_failed kind=HttpFailure code=503"
        assertEquals(
            "pre_session_auth_failed kind=HttpFailure code=503",
            DiagnosticReportBuilder.extractMessage(tight),
        )

        val processed = DiagnosticReportBuilder.processLine(line)
        assertTrue(processed!!.contains("session_established has_refresh=true"))
    }

    @Test
    fun build_includesHeaderAndDoesNotEmbedRawDumpNoise() {
        val raw = """
            08-01 12:00:01.000  1000  1001 I GetLineVPN: import_terminal success verdict=Consistent
            08-01 12:00:02.000  1000  1001 I GetLineVPN: App version: versionName = 0.1.12
        """.trimIndent()

        val report = DiagnosticReportBuilder.build(header, raw)
        assertTrue(report.contains("channel=prod"))
        assertTrue(report.contains("has_session=true"))
        assertTrue(report.contains("device=Xiaomi 2201117TY"))
        assertTrue(report.contains("import_terminal success verdict=Consistent"))
        assertFalse(report.contains("App version:"))
    }

    @Test
    fun build_emptyBuffer_hasPlaceholder() {
        val report = DiagnosticReportBuilder.build(header, "")
        assertTrue(report.contains("no allowlisted diagnostic events"))
        assertFalse(report.contains("log buffer dump failed"))
    }

    @Test
    fun build_nullDump_distinctFromEmptyBuffer() {
        val report = DiagnosticReportBuilder.build(header, rawLogcat = null)
        assertTrue(report.contains("log buffer dump failed"))
        assertFalse(report.contains("no allowlisted diagnostic events"))
    }

    @Test
    fun crashFilters_mustNotBeUsedAsReportSource() {
        // Document the contract for callers: report path uses diagnostic filters only.
        assertTrue(
            com.github.kr328.clash.log.SystemLogcat.diagnosticLogFilters
                .contentEquals(arrayOf("GetLineVPN:V")),
        )
        assertTrue(
            com.github.kr328.clash.log.SystemLogcat.crashLogFilters
                .contains("AndroidRuntime:E"),
        )
        assertFalse(
            com.github.kr328.clash.log.SystemLogcat.diagnosticLogFilters
                .any { it.startsWith("AndroidRuntime") },
        )
    }
}
