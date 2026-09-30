package com.freeturn.app.data.config

import java.io.InputStream
import java.net.InetAddress
import java.util.Base64
import java.util.zip.InflaterInputStream

/** Self-contained so rules survive profile cloning and encrypted backups. */
data class BypassRuleSet(val name: String, val format: String, val content: String) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(content)
    companion object {
        private val IPV6_LITERAL = Regex("[0-9a-fA-F:]+")
        private val IPV4_OCTET = Regex("[0-9]{1,3}")
        const val BINARY = "binary"
        const val CIDR = "cidr"
        const val BUILTIN_RU = "builtin-ru"
        fun defaults(): List<BypassRuleSet> = listOf(BypassRuleSet("ru-aggregated.zone", BUILTIN_RU, ""))
        const val MAX_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 4 * 1024 * 1024
        const val MAX_FILES = 8
        const val RU_URL = "https://www.ipdeny.com/ipblocks/data/aggregated/ru-aggregated.zone"

        fun readBounded(input: InputStream, limit: Int = MAX_BYTES): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= limit) { "Файл правил слишком большой (максимум 2 МБ)" }
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }

        fun fromBytes(name: String, bytes: ByteArray): BypassRuleSet {
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Пустой или слишком большой файл правил" }
            val binary = bytes.size >= 4 && bytes[0] == 83.toByte() && bytes[1] == 82.toByte() && bytes[2] == 83.toByte()
            val normalized = if (binary) {
                require(bytes[3].toInt() in 1..3) { "Эта версия .srs не поддерживается sing-box 1.12 (нужна версия 1–3)" }
                // Bound decompression and the first rule count before handing data to native code.
                val plain = InflaterInputStream(bytes.inputStream().apply { skip(4) }).use { readBounded(it, 16 * 1024 * 1024) }
                var count = 0L
                var shift = 0
                for (b in plain.take(10)) {
                    require(shift < 63) { "Некорректный .srs" }
                    count = count or ((b.toInt() and 127).toLong() shl shift)
                    if (b.toInt() and 128 == 0) break
                    shift += 7
                }
                require(plain.isNotEmpty() && shift < 63 && count in 1..100000) { "Пустой или некорректный .srs" }
                bytes
            } else {
                parseCidrs(bytes.toString(Charsets.UTF_8)).joinToString("\n").toByteArray(Charsets.UTF_8)
            }
            val safeName = name.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.take(100).ifBlank { if (binary) "rules.srs" else "rules.zone" }
            return BypassRuleSet(safeName, if (binary) BINARY else CIDR, Base64.getEncoder().encodeToString(normalized))
        }

        fun parseCidrs(text: String): List<String> {
            val networks = linkedSetOf<String>()
            text.removePrefix("\uFEFF").lineSequence().forEachIndexed { i, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isNotEmpty()) {
                    val parts = line.split('/')
                    require(parts.size == 2) { "Строка ${i + 1}: ожидается IP/префикс" }
                    val ip = parts[0]
                    val ipv6 = ':' in ip
                    val validIp = if (ipv6) {
                        IPV6_LITERAL.matches(ip) && runCatching { InetAddress.getByName(ip).address.size == 16 }.getOrDefault(false)
                    } else {
                        val octets = ip.split('.')
                        octets.size == 4 && octets.all { IPV4_OCTET.matches(it) && it.toInt() in 0..255 }
                    }
                    val prefix = parts[1].toIntOrNull()
                    require(validIp && prefix != null && prefix in 0..if (ipv6) 128 else 32) { "Строка ${i + 1}: некорректная IP-подсеть" }
                    networks += line
                }
            }
            require(networks.isNotEmpty()) { "Файл не содержит IP-подсетей" }
            require(networks.size <= 100000) { "Слишком много IP-подсетей" }
            return networks.toList()
        }

        fun add(current: List<BypassRuleSet>, rule: BypassRuleSet): List<BypassRuleSet> {
            // A refreshed file replaces the previous version with the same display name.
            val next = current.filterNot { it.name == rule.name } + rule
            require(next.size <= MAX_FILES && next.sumOf { it.content.length.toLong() } <= MAX_TOTAL_BYTES * 4L / 3) { "Слишком много файлов правил (до 8 файлов, всего до 4 МБ)" }
            return next
        }
    }
}
