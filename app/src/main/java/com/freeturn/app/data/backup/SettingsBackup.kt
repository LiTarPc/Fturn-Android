package com.freeturn.app.data.backup

import com.freeturn.app.data.config.ClientId
import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.json.JSONArray
import org.json.JSONObject

/** Содержимое бэкапа: профили + активный + личность устройства + интерфейсные тоггл-настройки. */
data class BackupData(
    val servers: List<Server>,
    val activeId: String?,
    // Постоянный client-id устройства (allowlist на сервере). Без него restore на новом
    // девайсе даёт свежий cid -> сервер отклоняет подключение, поэтому поле обязательное.
    val ownClientId: String,
    val dynamicTheme: Boolean,
    val nerdMode: Boolean,
    val privacyMode: Boolean,
    val restartServerOnSwitch: Boolean,
    val suppressUpdatePrompt: Boolean,
    val suppressTgPrompt: Boolean,
    val hotspotProxyEnabled: Boolean,
    val appTheme: String = com.freeturn.app.data.config.AppTheme.SYSTEM,
    val hotspotPort: Int = 1080,
    val hotspotPassword: String = "",
    val browserCaptcha: Boolean = true
)

/** Сериализация [BackupData] в JSON (серверы - через тот же [ServerJson], что и в DataStore). */
object SettingsBackup {
    private const val FORMAT_VERSION = 3

    fun encode(data: BackupData): String = JSONObject().apply {
        put("v", FORMAT_VERSION)
        put("servers", JSONArray(ServerJson.encodeList(data.servers)))
        data.activeId?.let { put("activeId", it) }
        put("ownClientId", data.ownClientId)
        put("dynamicTheme", data.dynamicTheme)
        put("appTheme", data.appTheme)
        put("nerdMode", data.nerdMode)
        put("privacyMode", data.privacyMode)
        put("browserCaptcha", data.browserCaptcha)
        put("restartServerOnSwitch", data.restartServerOnSwitch)
        put("suppressUpdatePrompt", data.suppressUpdatePrompt)
        put("suppressTgPrompt", data.suppressTgPrompt)
        put("hotspotProxyEnabled", data.hotspotProxyEnabled)
        put("hotspotPort", data.hotspotPort)
        put("hotspotPassword", data.hotspotPassword)
    }.toString()

    fun decode(json: String): BackupData {
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw BackupCrypto.FormatException("bad payload")
        }
        if (o.optInt("v", -1) !in 1..FORMAT_VERSION) throw BackupCrypto.FormatException("unsupported payload version")
        val serversJson = o.optJSONArray("servers")?.toString()
            ?: throw BackupCrypto.FormatException("missing profiles")
        val servers = try { ServerJson.decodeListStrict(serversJson) }
            catch (_: Exception) { throw BackupCrypto.FormatException("corrupted profiles") }
        val activeId = o.optString("activeId").takeIf { it.isNotBlank() }
        if (activeId != null && servers.none { it.id == activeId })
            throw BackupCrypto.FormatException("unknown active profile")
        // Битый/отсутствующий cid валим здесь, до затирания профиля: применить такой бэкап -
        // значит подменить личность на чужую и получить обрыв на allowlist.
        val cid = o.optString("ownClientId")
        if (!ClientId.isValid(cid)) throw BackupCrypto.FormatException("bad client id")
        return BackupData(
            servers = servers,
            activeId = activeId,
            ownClientId = cid,
            dynamicTheme = o.optBoolean("dynamicTheme", true),
            appTheme = com.freeturn.app.data.config.AppTheme.normalize(o.optString("appTheme")),
            nerdMode = o.optBoolean("nerdMode", true),
            privacyMode = o.optBoolean("privacyMode", false),
            browserCaptcha = o.optBoolean("browserCaptcha", true),
            restartServerOnSwitch = o.optBoolean("restartServerOnSwitch", false),
            suppressUpdatePrompt = o.optBoolean("suppressUpdatePrompt", false),
            suppressTgPrompt = o.optBoolean("suppressTgPrompt", false),
            hotspotProxyEnabled = o.optBoolean("hotspotProxyEnabled", false),
            hotspotPort = o.optInt("hotspotPort", 1080).takeIf { it in 1024..65535 } ?: 1080,
            hotspotPassword = o.optString("hotspotPassword").takeIf { it.toByteArray(Charsets.UTF_8).size in 8..255 } ?: ""
        )
    }
}
