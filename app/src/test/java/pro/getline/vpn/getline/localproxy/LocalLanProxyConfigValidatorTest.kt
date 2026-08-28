package pro.getline.vpn.getline.localproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The product validator has to agree with the Go processor
 * (`native/config/local_proxy.go`), or Enable fails somewhere the user cannot
 * see with nothing useful to say. These pin the cases where the two could
 * plausibly drift apart.
 */
class LocalLanProxyConfigValidatorTest {
    private fun config(
        port: Int = 12345,
        username: String = "getline",
        password: String = "correct-horse-battery",
    ) = LocalLanProxyUserConfig(port, username, password)

    private fun field(config: LocalLanProxyUserConfig) =
        (LocalLanProxyConfigValidator.validate(config) as? LocalLanProxyResult.InvalidSettings)?.field

    @Test
    fun acceptsAWorkingConfig() {
        assertNull(LocalLanProxyConfigValidator.validate(config()))
    }

    @Test
    fun rejectsPrivilegedAndOutOfRangePorts() {
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Port, field(config(port = 80)))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Port, field(config(port = 1023)))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Port, field(config(port = 65536)))
        assertNull(LocalLanProxyConfigValidator.validate(config(port = 1024)))
        assertNull(LocalLanProxyConfigValidator.validate(config(port = 65535)))
    }

    @Test
    fun rejectsColonInUsername() {
        // Accepted by SOCKS5, unusable over HTTP Basic: Mihomo splits at the
        // first colon, so this pair could never authenticate both ways.
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Username, field(config(username = "get:line")))
    }

    @Test
    fun rejectsEmptyWhitespaceAndNonAsciiCredentials() {
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Username, field(config(username = "")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Username, field(config(username = "get line")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Username, field(config(username = "пароль")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Password, field(config(password = "")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Password, field(config(password = "with space")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Password, field(config(password = "tab\there")))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Password, field(config(password = "ünïcode")))
    }

    @Test
    fun rejectsOverlongCredentials() {
        val long = "a".repeat(LocalLanProxyConfigValidator.MAX_CREDENTIAL_LENGTH + 1)

        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Username, field(config(username = long)))
        assertEquals(LocalLanProxyResult.InvalidSettings.Field.Password, field(config(password = long)))
    }

    @Test
    fun passwordIsNotPrintedByToString() {
        val rendered = config(password = "s3cret-value").toString()

        assert(!rendered.contains("s3cret-value")) { "password leaked into toString(): $rendered" }
    }
}
