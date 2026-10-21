package app.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values come from the Mac side (mac/Sources/GuardCore.swift, CryptoKit) for key = bytes 0..31. */
class GuardCryptoTest {
    private val key = ByteArray(32) { it.toByte() }
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hx(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun topicsMatchTheMac() {
        assertEquals("pbg-9369e8a82a1bd6325d40b07a", GuardCrypto.topic(key, "topic-out"))
        assertEquals("pbg-03f0728a1273d9dc5befa7ff", GuardCrypto.topic(key, "topic-cmd"))
    }

    @Test
    fun authenticatorMatchesTheMac() {
        assertEquals("7cf096470340404a77eae0dede54dbb61927d508", hx(GuardCrypto.totpSeed(key)))
        val seed = GuardCrypto.totpSeed(key)
        assertEquals("717923", GuardCrypto.totp(seed, 59L / 30))
        assertEquals("397227", GuardCrypto.totp(seed, 1_111_111_109L / 30))
        assertEquals("530082", GuardCrypto.totp(seed, 2_000_000_000L / 30))
    }

    @Test
    fun rfc6238Vector() {
        assertEquals("287082", GuardCrypto.totp("12345678901234567890".toByteArray(), 59L / 30))
    }

    @Test
    fun signatureMatchesTheMac() {
        assertEquals("9b35ba294ebddd49235f079c3bc10ead54a0b6c0749629db6340a6edb3d564f4", hx(GuardCrypto.sign(key, "hello".toByteArray())))
    }

    @Test
    fun opensWhatTheMacSealed() {
        val sealed = hex("ccd807342363a145f7692ebb21f65dc72d8f4e3a4c72d64afc67a89cee4b1cfcea394af49fc8ff0cab39fc")
        assertEquals("photo-test-📷", String(GuardCrypto.decrypt(key, sealed)))
    }
}
