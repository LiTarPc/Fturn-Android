package com.freeturn.app.data.backup

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCryptoTest {
    private fun envelope() = JSONObject().put("magic", "freeturn-backup").put("v", 1)
        .put("kdf", "pbkdf2-sha256").put("iter", 210000)
        .put("salt", "AA==").put("iv", "AA==").put("ct", "AA==")
    private fun invalid(json: JSONObject) {
        assertThrows(BackupCrypto.FormatException::class.java) { BackupCrypto.decrypt(json.toString().toByteArray(), "password") }
    }
    @Test fun `encrypted export round trips UTF8 and empty content`() {
        for (text in listOf("", "Настройки VPN")) {
            val input = text.toByteArray(Charsets.UTF_8)
            assertArrayEquals(input, BackupCrypto.decrypt(BackupCrypto.encrypt(input, "пароль"), "пароль"))
        }
    }
    @Test fun `wrong password is distinct from a malformed file`() {
        val bytes = BackupCrypto.encrypt("test".toByteArray(), "password")
        assertThrows(BackupCrypto.BadPasswordException::class.java) { BackupCrypto.decrypt(bytes, "wrong") }
    }
    @Test fun `rejects unbounded or missing KDF work before deriving a key`() {
        for (iter in listOf(-1L, 0L, 1_000_001L, Long.MAX_VALUE)) invalid(envelope().put("iter", iter))
        invalid(envelope().apply { remove("iter") })
    }
    @Test fun `unknown envelope versions or KDF algorithms are rejected`() {
        invalid(envelope().put("v", 2))
        invalid(envelope().put("kdf", "unknown"))
        invalid(envelope().apply { remove("v") })
    }
    @Test fun `rejects bad base64 and invalid cryptographic lengths`() {
        invalid(envelope())
        invalid(envelope().put("salt", "?"))
        val good = JSONObject(String(BackupCrypto.encrypt("test".toByteArray(), "password")))
        invalid(JSONObject(good.toString()).put("iv", "AA=="))
        invalid(JSONObject(good.toString()).put("ct", "AA=="))
    }
}
