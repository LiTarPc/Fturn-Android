package com.freeturn.app.data.backup

import java.util.Base64
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрует бэкап паролем: PBKDF2-SHA256 (BouncyCastle - одинаков на всех API, в отличие
 * от javax PBKDF2WithHmacSHA256 с API 26) + AES-256-GCM. Конверт - JSON с base64-полями.
 */
object BackupCrypto {
    // Includes encrypted JSON and base64 rule files for several profiles.
    const val MAX_ENVELOPE_BYTES = 64 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 47 * 1024 * 1024
    private const val MAGIC = "freeturn-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Неверный пароль (не сошёлся GCM-тег). */
    class BadPasswordException : Exception()
    /** Файл не является бэкапом FreeTurn / повреждён. */
    class FormatException(message: String) : Exception(message)

    fun encrypt(plaintext: ByteArray, password: String): ByteArray {
        if (plaintext.size > MAX_PAYLOAD_BYTES) throw FormatException("backup is too large")
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(password, salt, ITERATIONS), "AES"),
                GCMParameterSpec(TAG_BITS, iv))
        }
        val ct = cipher.doFinal(plaintext)
        return JSONObject().apply {
            put("magic", MAGIC)
            put("v", VERSION)
            put("kdf", "pbkdf2-sha256")
            put("iter", ITERATIONS)
            put("salt", b64(salt))
            put("iv", b64(iv))
            put("ct", b64(ct))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decrypt(envelope: ByteArray, password: String): ByteArray {
        if (envelope.size > MAX_ENVELOPE_BYTES) throw FormatException("backup is too large")
        val o = try {
            JSONObject(String(envelope, Charsets.UTF_8))
        } catch (_: Exception) {
            throw FormatException("not a backup file")
        }
        if (o.optString("magic") != MAGIC) throw FormatException("not a FreeTurn backup")
        if (o.optInt("v", -1) != VERSION || o.optString("kdf") != "pbkdf2-sha256")
            throw FormatException("unsupported backup format")
        val iter = o.optLong("iter", -1)
        if (iter !in 1L..1_000_000L) throw FormatException("unsupported iteration count")
        val salt = unb64(o, "salt")
        val iv = unb64(o, "iv")
        val ct = unb64(o, "ct")
        if (salt.size != SALT_LEN || iv.size != IV_LEN || ct.size < TAG_BITS / 8 || ct.size > MAX_PAYLOAD_BYTES + TAG_BITS / 8)
            throw FormatException("corrupted backup")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(password, salt, iter.toInt()), "AES"),
                GCMParameterSpec(TAG_BITS, iv))
        }
        return try {
            cipher.doFinal(ct)
        } catch (_: Exception) {
            throw BadPasswordException()
        }
    }

    private fun deriveKey(password: String, salt: ByteArray, iter: Int): ByteArray {
        val gen = PKCS5S2ParametersGenerator(SHA256Digest())
        gen.init(password.toByteArray(Charsets.UTF_8), salt, iter)
        return (gen.generateDerivedParameters(KEY_BITS) as KeyParameter).key
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun unb64(o: JSONObject, key: String): ByteArray = try {
        Base64.getDecoder().decode(o.getString(key))
    } catch (_: Exception) {
        throw FormatException("corrupted backup")
    }
}
