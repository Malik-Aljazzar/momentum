package com.momentum.app.data

import kotlinx.serialization.Serializable
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the server stores: one wrapped message key per reader. */
@Serializable
data class Envelope(val v: Int, val eph: String, val spk: String, val ct: String, val pv: String, val keys: Map<String, String>)

/** What one reader gets back: their wrapped key, the preview, the body unless it's only a preview. */
@Serializable
data class ReaderEnvelope(val v: Int = 1, val eph: String, val spk: String, val pv: String, val ct: String? = null, val key: String? = null)

/** A message's body: the text and, for a photo / voice message, its key and type. */
@Serializable data class E2EBody(val t: String = "", val m: E2EMediaKey? = null)
@Serializable data class E2EMediaKey(val k: String, val mime: String = "")
/** The preview (chat list, quotes, notifications): the start of the text, "image" / "audio". */
@Serializable data class E2EPreview(val t: String = "", val k: String? = null)

/**
 * The chat encryption (the scheme is described in server/e2e.py; the same in TypeScript: web/src/e2ecrypto.ts,
 * in Python: server/tests/e2e_crypto.py). Pure functions on the JCA - the unit test opens the shared vector.
 */
object E2E {
    private val rnd = SecureRandom()

    fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
    fun random(n: Int) = ByteArray(n).also(rnd::nextBytes)

    private val curve: ECParameterSpec by lazy {
        (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey).params
    }

    /** HKDF-SHA256, 32 bytes (one block). */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info.toByteArray())
        mac.update(1)
        return mac.doFinal()
    }

    /** iv || AES-256-GCM(key, iv, plain, aad) */
    fun seal(key: ByteArray, plain: ByteArray, aad: String? = null): ByteArray {
        val iv = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad.toByteArray())
        return iv + c.doFinal(plain)
    }

    fun unseal(key: ByteArray, data: ByteArray, aad: String? = null): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        if (aad != null) c.updateAAD(aad.toByteArray())
        return c.doFinal(data, 12, data.size - 12)
    }

    private fun fixed(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** A public key as the raw uncompressed point (65 bytes). */
    fun raw(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
    }

    fun publicKey(value: String): PublicKey {
        val r = unb64(value)
        require(r.size == 65 && r[0] == 4.toByte()) { "not a P-256 point" }
        val point = ECPoint(BigInteger(1, r.copyOfRange(1, 33)), BigInteger(1, r.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
    }

    fun privateKey(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(priv); doPhase(pub, true) }.generateSecret()

    /** A new key pair: the private key as PKCS#8 and the public key (raw point, base64). */
    fun newIdentity(): Pair<ByteArray, String> {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        return kp.private.encoded to b64(raw(kp.public))
    }

    /** Does this public key belong to the private key? (ECDH with a fresh key from both sides gives the same.) */
    fun matches(priv: PrivateKey, pub: String): Boolean = runCatching {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        ecdh(kp.private, publicKey(pub)).contentEquals(ecdh(priv, kp.public))
    }.getOrDefault(false)

    // ---------------------------------------------------------------- recovery key + backup

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** 32 bytes as 52 letters/digits in groups of four ("ABCD EFGH ..."). */
    fun formatRecovery(bytes: ByteArray): String {
        val out = StringBuilder()
        var bits = 0
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 255)
            bits += 8
            while (bits >= 5) { out.append(B32[(value ushr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(B32[(value shl (5 - bits)) and 31])
        return out.chunked(4).joinToString(" ")
    }

    /** What the player typed (spaces, dashes, small letters, 0/1/8 for O/I/B are fine) -> 32 bytes, or null. */
    fun parseRecovery(text: String): ByteArray? {
        val clean = text.uppercase().replace('0', 'O').replace('1', 'I').replace('8', 'B').filter { it in B32 }
        if (clean.length != 52) return null
        val out = ByteArray(32)
        var bits = 0
        var value = 0
        var at = 0
        for (ch in clean) {
            value = (value shl 5) or B32.indexOf(ch)
            bits += 5
            if (bits >= 8 && at < 32) { out[at++] = ((value ushr (bits - 8)) and 255).toByte(); bits -= 8 }
        }
        return if (at == 32) out else null
    }

    private fun backupKey(recovery: ByteArray) = hkdf(recovery, ByteArray(32), "momentum-e2e-backup-v1")

    fun makeBackup(pkcs8: ByteArray, recovery: ByteArray): String = b64(seal(backupKey(recovery), pkcs8, "momentum-backup"))

    /** The private key (PKCS#8) from the server's backup - null: wrong recovery key. */
    fun openBackup(backup: String, recovery: ByteArray): ByteArray? =
        runCatching { unseal(backupKey(recovery), unb64(backup), "momentum-backup") }.getOrNull()

    // ---------------------------------------------------------------- messages

    private fun wrapInfo(conv: Int, uid: Int) = "momentum-wrap-v1:$conv:$uid"

    /** [readers]: user id -> public key. [body] / [preview]: JSON. */
    fun encryptEnvelope(priv: PrivateKey, pub: String, conv: Int, readers: Map<Int, String>, body: String, preview: String): Envelope {
        val k = random(32)
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        val ephRaw = raw(eph.public)
        val keys = readers.mapKeys { it.key.toString() }.mapValues { (uid, value) ->
            val reader = publicKey(value)
            val wk = hkdf(ecdh(eph.private, reader) + ecdh(priv, reader), ephRaw, wrapInfo(conv, uid.toInt()))
            b64(seal(wk, k))
        }
        val aad = "momentum-msg-v1:$conv"
        return Envelope(1, b64(ephRaw), pub, b64(seal(k, body.toByteArray(), aad)), b64(seal(k, preview.toByteArray(), "$aad:pv")), keys)
    }

    /** The body ("ct") or the preview ("pv") as JSON - null: not for me / damaged / not from that key. */
    fun openEnvelope(priv: PrivateKey, conv: Int, uid: Int, env: ReaderEnvelope, preview: Boolean): String? = runCatching {
        val data = (if (preview) env.pv else env.ct) ?: return null
        val wrapped = env.key ?: return null
        val ephRaw = unb64(env.eph)
        val z = ecdh(priv, publicKey(env.eph)) + ecdh(priv, publicKey(env.spk))
        val k = unseal(hkdf(z, ephRaw, wrapInfo(conv, uid)), unb64(wrapped))
        String(unseal(k, unb64(data), "momentum-msg-v1:$conv" + if (preview) ":pv" else ""))
    }.getOrNull()

    // ---------------------------------------------------------------- media, safety codes

    fun encryptMedia(key: ByteArray, data: ByteArray) = seal(key, data)
    fun decryptMedia(key: String, data: ByteArray) = unseal(unb64(key), data)

    /** 30 digits from a public key: people compare them in person (the same on both phones = nobody in between). */
    fun safetyCode(pub: String): String {
        val h = MessageDigest.getInstance("SHA-256").digest(unb64(pub))
        return (0 until 6).joinToString(" ") { i ->
            var n = 0L
            for (j in 0 until 5) n = n * 256 + (h[i * 5 + j].toInt() and 255)
            (n % 100000).toString().padStart(5, '0')
        }
    }
}
