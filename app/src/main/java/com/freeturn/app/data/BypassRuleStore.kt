package com.freeturn.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.freeturn.app.data.config.BypassRuleSet
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Copies imported rules into app storage; no dependency on external file permissions. */
class BypassRuleStore(private val context: Context) {
    fun importFile(uri: Uri): BypassRuleSet {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
        val bytes = context.contentResolver.openInputStream(uri)?.use { BypassRuleSet.readBounded(it) }
            ?: error("Не удалось открыть файл")
        return checked(BypassRuleSet.fromBytes(name, bytes))
    }

    fun downloadRussia(): BypassRuleSet {
        val connection = URL(BypassRuleSet.RU_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        try {
            require(connection.responseCode == 200) { "IPdeny: HTTP ${connection.responseCode}" }
            val bytes = connection.inputStream.use { BypassRuleSet.readBounded(it) }
            return checked(BypassRuleSet.fromBytes("ru-aggregated.zone", bytes))
        } finally { connection.disconnect() }
    }

    private fun checked(rule: BypassRuleSet): BypassRuleSet {
        // The exact embedded sing-box version validates binary contents and rule semantics.
        val sets = materialize(listOf(rule))
        val json = JSONObject().put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
            .put("route", JSONObject().put("rule_set", sets).put("rules", JSONArray().put(JSONObject()
                .put("rule_set", "bypass-0").put("outbound", "direct"))))
        Libbox.checkConfig(json.toString())
        return rule
    }

    fun materialize(rules: List<BypassRuleSet>): JSONArray = JSONArray().apply {
        rules.forEachIndexed { i, stored ->
            val rule = if (stored.format == BypassRuleSet.BUILTIN_RU) {
                require(stored.name == "ru-aggregated.zone" && stored.content.isEmpty()) { "Некорректный встроенный список" }
                context.assets.open("rules/ru-aggregated.zone").use { BypassRuleSet.fromBytes(stored.name, BypassRuleSet.readBounded(it)) }
            } else BypassRuleSet.fromBytes(stored.name, stored.bytes()).also {
                require(it.format == stored.format) { "Некорректный формат файла правил" }
            }
            val item = JSONObject().put("tag", "bypass-$i")
            if (rule.format == BypassRuleSet.CIDR) {
                item.put("type", "inline").put("rules", JSONArray().put(JSONObject().put("ip_cidr",
                    JSONArray(BypassRuleSet.parseCidrs(rule.bytes().toString(Charsets.UTF_8))))))
            } else {
                val bytes = rule.bytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val dir = context.filesDir.resolve("sing-box/rule-sets").apply { mkdirs() }
                val file = dir.resolve("$hash.srs")
                if (!file.exists()) file.writeBytes(bytes)
                item.put("type", "local").put("format", "binary").put("path", file.absolutePath)
            }
            put(item)
        }
    }
}
