package pro.getline.vpn.getline.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class NativeAuthPkceTest {
    @Test
    fun generate_verifierLengthAndAlphabet() {
        val pair = NativeAuthPkce.generate(SecureRandom(byteArrayOf(1, 2, 3, 4)))
        assertEquals(NativeAuthPkce.VERIFIER_LENGTH, pair.verifier.length)
        assertTrue(pair.verifier.all { it.isLetterOrDigit() || it in "-._~" })
        assertTrue(pair.challenge.isNotBlank())
        assertTrue(!pair.challenge.contains("="))
        assertTrue(!pair.challenge.contains("+"))
        assertTrue(!pair.challenge.contains("/"))
    }

    @Test
    fun challengeS256_matchesRfc7636AppendixB() {
        // RFC 7636 Appendix B test vector.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expected = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        assertEquals(expected, NativeAuthPkce.challengeS256(verifier))
    }

    @Test
    fun generate_isNotDeterministicAcrossCalls() {
        val a = NativeAuthPkce.generate()
        val b = NativeAuthPkce.generate()
        assertNotEquals(a.verifier, b.verifier)
    }
}
