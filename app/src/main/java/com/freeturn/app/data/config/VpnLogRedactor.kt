package com.freeturn.app.data.config

import java.util.Base64

/** Native WireGuard IPC errors may contain base64 or hexadecimal keys. */
class VpnLogRedactor(cfg: ClientConfig) {
    private val secrets = buildList {
        if (cfg.vlessUri.isNotBlank()) add(cfg.vlessUri)
        runCatching { VlessProfile.parse(cfg.vlessUri).uuid }.getOrNull()?.let(::add)
        Regex("(?im)^\\s*(?:PrivateKey|PresharedKey|PublicKey)\\s*=\\s*(\\S+)")
            .findAll(cfg.wireGuardConfig).forEach { match ->
                val key = match.groupValues[1]
                add(key)
                runCatching { Base64.getDecoder().decode(key).joinToString("") { "%02x".format(it) } }
                    .getOrNull()?.let(::add)
            }
    }
    fun redact(message: String): String = secrets.fold(message) { safe, secret ->
        safe.replace(secret, "<credential>", ignoreCase = true)
    }
}
