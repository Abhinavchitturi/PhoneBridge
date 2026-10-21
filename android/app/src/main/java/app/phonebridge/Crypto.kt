package app.phonebridge

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * X25519 identity + session crypto. Must stay byte-for-byte compatible with mac/Sources/Crypto.swift:
 *  - pairing code = first 4 bytes (big-endian) of SHA-256(static shared) mod 1,000,000
 *  - session keys = HKDF-SHA256(ikm = ss || ee || es || se, salt = macNonce || phoneNonce, info = "m2p" / "p2m")
 *    where s = long-term (pairing) keys, e = fresh per-connection keys, first letter = the Mac's key.
 *    The ephemeral keys give forward secrecy: a stolen pairing key can't decrypt earlier sessions.
 *  - AES-256-GCM, nonce = 4 zero bytes || 8-byte big-endian counter, wire = ciphertext || tag
 * Secrets at rest (our private key, the paired Mac's key) are encrypted with a hardware-backed Android Keystore key.
 */
object Crypto {
    private lateinit var priv: ByteArray
    lateinit var pubRaw: ByteArray
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (::priv.isInitialized) return
        val p = Prefs.get(ctx)
        val sealed = p.getString("privSealed", null)
        val legacy = p.getString("priv32", null) // older versions stored it unencrypted
        priv = when {
            sealed != null -> Vault.open(sealed)
            legacy != null -> b64d(legacy)
            else -> ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        if (sealed == null) p.edit().putString("privSealed", Vault.seal(priv)).remove("priv32").apply()
        pubRaw = X25519.publicKey(priv)
    }

    /** The paired Mac's public key, sealed so it can't be swapped by tampering with app storage. */
    fun trustedMac(ctx: Context): ByteArray? {
        val p = Prefs.get(ctx)
        p.getString("macSealed", null)?.let { return runCatching { Vault.open(it) }.getOrNull() }
        val legacy = p.getString("macPub", null) ?: return null
        val key = b64d(legacy)
        setTrustedMac(ctx, key)
        return key
    }

    fun setTrustedMac(ctx: Context, key: ByteArray?) {
        val e = Prefs.get(ctx).edit().remove("macPub")
        if (key == null) e.remove("macSealed") else e.putString("macSealed", Vault.seal(key))
        e.apply()
    }

    class Ephemeral {
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val pub: ByteArray = X25519.publicKey(priv)
    }

    /** ss || ee || es || se in the Mac's order (see the header), computed from the phone's side. */
    fun sessionIkm(eph: Ephemeral, macStatic: ByteArray, macEph: ByteArray): ByteArray =
        sessionIkm(priv, eph.priv, macStatic, macEph)

    fun sessionIkm(myStatic: ByteArray, myEph: ByteArray, macStatic: ByteArray, macEph: ByteArray): ByteArray {
        fun dh(a: ByteArray, b: ByteArray): ByteArray {
            require(b.size == 32) { "bad public key" }
            return X25519.sharedSecret(a, b).also { s -> require(s.any { it != 0.toByte() }) { "invalid public key" } }
        }
        return dh(myStatic, macStatic) + dh(myEph, macEph) + dh(myStatic, macEph) + dh(myEph, macStatic)
    }

    fun shared(peerRaw: ByteArray): ByteArray {
        require(peerRaw.size == 32) { "bad public key" }
        val s = X25519.sharedSecret(priv, peerRaw)
        require(s.any { it != 0.toByte() }) { "invalid public key" }
        return s
    }

    fun pairingCode(shared: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(shared)
        val n = ByteBuffer.wrap(h, 0, 4).int.toLong() and 0xffffffffL
        return "%06d".format(n % 1_000_000)
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray())
        mac.update(1.toByte())
        return mac.doFinal()
    }

    fun b64e(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun b64d(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}

/** One direction of an encrypted session; the counter makes every nonce unique. */
class Sealer(key: ByteArray) {
    private val key = SecretKeySpec(key, "AES")
    private var counter = 0L

    fun seal(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, plain)
    fun open(sealed: ByteArray): ByteArray = run(Cipher.DECRYPT_MODE, sealed)

    private fun run(mode: Int, data: ByteArray): ByteArray {
        val nonce = ByteBuffer.allocate(12).putInt(0).putLong(counter++).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(128, nonce))
        return c.doFinal(data)
    }
}

object Prefs {
    fun get(ctx: Context) = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)
}

/** AES-GCM with a non-exportable key in the Android Keystore (secure hardware where available). */
object Vault {
    private const val ALIAS = "phonebridge_vault"

    private fun key(): javax.crypto.SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
        fun generate(strongBox: Boolean): javax.crypto.SecretKey {
            val gen = javax.crypto.KeyGenerator.getInstance(android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            gen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setIsStrongBoxBacked(strongBox)
                    .build()
            )
            return gen.generateKey()
        }
        return try { generate(strongBox = true) } catch (e: Exception) { generate(strongBox = false) }
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Crypto.b64e(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray {
        val data = Crypto.b64d(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return c.doFinal(data, 12, data.size - 12)
    }
}
