package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

class X25519Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // RFC 7748 section 6.1
    @Test
    fun rfc7748Vectors() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hx(X25519.publicKey(alice)))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hx(X25519.publicKey(bob)))
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, hx(X25519.sharedSecret(alice, X25519.publicKey(bob))))
        assertEquals(shared, hx(X25519.sharedSecret(bob, X25519.publicKey(alice))))
    }

    // Same keys as the Mac-side check (CryptoKit): private keys 0x01*32 and 0x02*32.
    @Test
    fun matchesMacCryptoKit() {
        val a = ByteArray(32) { 1 }
        val b = ByteArray(32) { 2 }
        assertEquals(
            "2ed76ab549b1e73c031eb49c9448f0798aea81b698279a0c3dc3e49fbfc4b953",
            hx(X25519.sharedSecret(a, X25519.publicKey(b))),
        )
        assertEquals("431591", Crypto.pairingCode(X25519.sharedSecret(a, X25519.publicKey(b))))
    }
}
