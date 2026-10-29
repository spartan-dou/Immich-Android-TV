package nl.giejay.android.tv.immich.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichLoginClientPkceTest {

    @Test
    fun codeChallengeMatchesRfc7636Example() {
        // RFC 7636, appendix B
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ImmichLoginClient.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test
    fun base64UrlMatchesJavaEncoderForAllLengths() {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        for (size in 0..40) {
            val bytes = ByteArray(size) { (it * 37 + 200).toByte() }
            assertEquals("size $size", encoder.encodeToString(bytes), ImmichLoginClient.base64Url(bytes))
        }
    }

    @Test
    fun verifierIsUrlSafeAndRandom() {
        val verifier = ImmichLoginClient.newCodeVerifier()
        // 32 random bytes -> 43 base64url characters, within RFC 7636's 43..128
        assertEquals(43, verifier.length)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(verifier, ImmichLoginClient.newCodeVerifier())
    }
}
