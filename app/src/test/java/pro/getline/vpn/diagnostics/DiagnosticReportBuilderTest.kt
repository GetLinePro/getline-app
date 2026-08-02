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
    fun allowlist_keepsStartupRouteAndRepair_slice1() {
        // Typical double-login / cold-start report: prior auth → route → repair.
        // Fields not evaluated on a branch stay literal "na" (not fabricated).
        // store=err vs session=0: keystore/store failure is not an empty session.
        // session=/managed= shared key names with repair_outcome.
        val raw = """
            08-01 12:00:01.000  1000  1001 I GetLineVPN: session_established has_refresh=true binding=true
            08-01 12:00:02.000  1000  1001 I GetLineVPN: import_terminal success verdict=Consistent
            08-01 12:00:03.000  1000  1001 I GetLineVPN: startup_route dest=home reason=managed_profile store=ok session=1 managed=1 pending_import=0 imported=na backend=na
            08-01 12:00:04.000  1000  1001 I GetLineVPN: repair_outcome outcome=Ready step=na online=1 allow_net=0 session=1 managed=1
            08-01 12:00:05.000  1000  1001 I GetLineVPN: NetworkObserve onAvailable network=123
            08-01 12:00:06.000  1000  1001 I GetLineVPN: startup_route dest=onboarding reason=no_import store=ok session=0 managed=0 pending_import=0 imported=0 backend=ok
            08-01 12:00:07.000  1000  1001 I GetLineVPN: startup_route dest=home reason=backend_unavailable store=ok session=1 managed=0 pending_import=0 imported=na backend=unavailable
            08-01 12:00:08.000  1000  1001 I GetLineVPN: repair_outcome outcome=FailedRestore step=OfflineForRemote online=0 allow_net=1 session=1 managed=0
            08-01 12:00:09.000  1000  1001 I GetLineVPN: repair_outcome outcome=FailedRestore step=RemoteReprovision online=1 allow_net=1 session=1 managed=1
            08-01 12:00:10.000  1000  1001 I GetLineVPN: startup_route dest=onboarding reason=no_import store=err session=0 managed=0 pending_import=0 imported=0 backend=ok
            08-01 12:00:11.000  1000  1001 I GetLineVPN: repair something else noise
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(9, lines.size)
        assertTrue(lines[0].contains("session_established"))
        assertTrue(lines[1].contains("import_terminal"))
        assertTrue(lines[2].contains("startup_route dest=home reason=managed_profile store=ok"))
        assertTrue(lines[2].contains("imported=na backend=na"))
        assertTrue(lines[3].contains("repair_outcome outcome=Ready step=na"))
        assertTrue(lines[3].contains("session=1 managed=1"))
        assertTrue(lines[4].contains("startup_route dest=onboarding reason=no_import store=ok"))
        assertTrue(lines[5].contains("startup_route dest=home reason=backend_unavailable"))
        assertTrue(lines[6].contains("repair_outcome outcome=FailedRestore step=OfflineForRemote"))
        assertTrue(lines[7].contains("repair_outcome outcome=FailedRestore step=RemoteReprovision"))
        assertTrue(lines[8].contains("store=err session=0"))
        assertEquals(4, lines.count { it.contains("startup_route") })
        assertEquals(3, lines.count { it.contains("repair_outcome") })
        assertFalse(lines.any { it.contains("NetworkObserve") })
        // Bare "repair …" must not match the compound event name.
        assertFalse(lines.any { it.contains("repair something else") })
    }

    @Test
    fun allowlist_keepsVpnConnectChain_slice2() {
        // connect → permission → requested → started, or timeout / failed.
        // vpn_state is observed (not causal); bare ui noise stays out.
        val raw = """
            08-01 13:00:01.000  1000  1001 I GetLineVPN: vpn_ui action=connect_clicked
            08-01 13:00:02.000  1000  1001 I GetLineVPN: repair_outcome outcome=Ready step=na online=1 allow_net=1 session=1 managed=1
            08-01 13:00:03.000  1000  1001 I GetLineVPN: vpn_start stage=permission_needed
            08-01 13:00:04.000  1000  1001 I GetLineVPN: vpn_start stage=permission_result result=ok
            08-01 13:00:05.000  1000  1001 I GetLineVPN: vpn_start stage=requested path=after_permission
            08-01 13:00:06.000  1000  1001 I GetLineVPN: vpn_state value=started
            08-01 13:00:07.000  1000  1001 I GetLineVPN: vpn_ui action=disconnect_clicked
            08-01 13:00:08.000  1000  1001 I GetLineVPN: vpn_state value=stopped
            08-01 13:00:09.000  1000  1001 I GetLineVPN: vpn_start stage=requested path=direct
            08-01 13:00:10.000  1000  1001 W GetLineVPN: vpn_start stage=timeout
            08-01 13:00:11.000  1000  1001 W GetLineVPN: vpn_start stage=failed kind=SecurityException
            08-01 13:00:12.000  1000  1001 I GetLineVPN: vpn_state value=service_recreated
            08-01 13:00:13.000  1000  1001 I GetLineVPN: vpn_ui action=connect_ignored reason=connecting
            08-01 13:00:14.000  1000  1001 W GetLineVPN: vpn_start stage=permission_still_needed path=after_permission
            08-01 13:00:15.000  1000  1001 I GetLineVPN: ui connect_clicked
            08-01 13:00:16.000  1000  1001 I GetLineVPN: Create clash runtime: secret path
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(14, lines.size)
        assertTrue(lines[0].contains("vpn_ui action=connect_clicked"))
        assertTrue(lines[1].contains("repair_outcome outcome=Ready"))
        assertTrue(lines[2].contains("vpn_start stage=permission_needed"))
        assertTrue(lines[4].contains("stage=requested path=after_permission"))
        assertTrue(lines[5].contains("vpn_state value=started"))
        assertTrue(lines[6].contains("vpn_ui action=disconnect_clicked"))
        assertTrue(lines[7].contains("vpn_state value=stopped"))
        assertTrue(lines[8].contains("path=direct"))
        assertTrue(lines[9].contains("vpn_start stage=timeout"))
        assertTrue(lines[10].contains("vpn_start stage=failed kind=SecurityException"))
        assertTrue(lines[11].contains("vpn_state value=service_recreated"))
        assertTrue(lines[12].contains("vpn_ui action=connect_ignored reason=connecting"))
        assertTrue(lines[13].contains("vpn_start stage=permission_still_needed"))
        assertEquals(3, lines.count { it.contains("vpn_ui ") })
        assertEquals(7, lines.count { it.contains("vpn_start ") })
        assertEquals(3, lines.count { it.contains("vpn_state ") })
        assertFalse(lines.any { Regex("""(?<!vpn_)ui connect_clicked""").containsMatchIn(it) })
        assertFalse(lines.any { it.contains("Create clash runtime") })
        assertFalse(lines.any { it.contains("secret path") })
    }

    @Test
    fun allowlist_keepsSafeProfileImportStages_butDropsRawFetchArgs() {
        val raw = """
            08-02 12:52:25.300  1000  1001 I GetLineVPN: profile_import start op=12ab34cd reuse=0
            08-02 12:52:25.400  1000  1001 I GetLineVPN: profile_import stage=remote_acquired op=12ab34cd
            08-02 12:52:25.500  1000  1001 I GetLineVPN: profile_import stage=profile_prepared op=12ab34cd reused=0
            08-02 12:52:25.600  1000  1001 I GetLineVPN: profile_import stage=commit_begin op=12ab34cd
            08-02 12:52:25.700  1000  1001 D GetLineVPN: fetch action=FetchConfiguration args=https://secret.example/sub progress=1/2
            08-02 12:52:25.800  1000  1001 I GetLineVPN: profile_import fetch op=12ab34cd action=FetchConfiguration
            08-02 12:52:31.900  1000  1001 I GetLineVPN: profile_import cleanup op=12ab34cd outcome=ok
            08-02 12:52:32.000  1000  1001 W GetLineVPN: profile_import end op=12ab34cd outcome=unavailable kind=IOException elapsed_ms=6700
        """.trimIndent()

        val lines = DiagnosticReportBuilder.selectEventLines(raw)
        assertEquals(7, lines.size)
        assertTrue(lines.first().contains("profile_import start op=12ab34cd"))
        assertTrue(lines.any { it.contains("stage=commit_begin") })
        assertTrue(lines.any { it.contains("action=FetchConfiguration") })
        assertTrue(lines.any { it.contains("cleanup op=12ab34cd outcome=ok") })
        assertTrue(lines.last().contains("outcome=unavailable kind=IOException"))
        assertFalse(lines.any { it.contains("args=") })
        assertFalse(lines.any { it.contains("secret.example") })
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
