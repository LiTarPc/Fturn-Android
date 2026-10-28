package com.freeturn.app.domain.diagnostics

/** Persistent/exported logs never depend on the UI privacy switch. */
object DiagnosticRedactor {
    private val ansi = Regex("\\x1b\\[[0-?]*[ -/]*[@-~]")
    private val profile = Regex("(?i)\\b(?:fturnraw|freeturn|vless|vmess|ss|trojan|wireguard)://\\S+")
    private val call = Regex("(?i)https?://(?:vk\\.ru|vk\\.com)/call/join/[^\\s\"']+")
    private val flags = Regex("(?i)(--?(?:obf-key|raw-key|private-key|preshared-key|password|passwd|token|access-token|secret|client-id|links?)(?:=|\\s+))(\"[^\"]*\"|'[^']*'|\\S+)")
    private val bearer = Regex("(?i)(authorization\\s*[:=]\\s*)(?:Bearer|Basic)\\s+[^\\s\"']+")
    private val fields = Regex("(?i)((?<![a-z0-9_])[\"']?(?:access[_-]?token|refresh[_-]?token|id[_-]?token|authorization|password|passwd|secret|private[_-]?key|preshared[_-]?key|obf[_-]?key|raw[_-]?key|turn[_-]?(?:user(?:name)?|password|credential)|username|client[_-]?id|captcha[_-]?(?:sid|key|token)|token|credential|nonce|key)[\"']?\\s*[:=]\\s*)(\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'|[^\\s,;}\\]]+)")
    private val url = Regex("(?i)https?://[^\\s\"'<>]+")
    private val userInfo = Regex("(?<=://)[^/\\s@]+@")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")

    private val captchaBody = Regex("(?i)\\bdata:\\s*|pow envelope:\\s*")

    // Also applied on export to captcha bodies recorded by previous app versions.
    fun redactLegacyCaptchaPayload(message: String): String {
        if (!message.contains("[Captcha", ignoreCase = true)) return message
        val body = captchaBody.find(message) ?: return message
        return message.substring(0, body.range.last + 1) + "<redacted>"
    }

    fun redact(message: String, secrets: List<String> = emptyList()): String {
        if (message.length > 64 * 1024) return "[oversized log entry omitted: ${message.length} characters]"
        var safe = redactLegacyCaptchaPayload(message)
        secrets.filter { it.length >= 4 }.forEach { safe = safe.replace(it, "<credential>") }
        safe = ansi.replace(safe, "")
        val params = safe.indexOf("request_params", ignoreCase = true)
        if (params >= 0) safe = safe.substring(0, params) + "request_params=<redacted>"
        safe = profile.replace(safe, "<profile-link>")
        safe = call.replace(safe, "<VK-call>")
        safe = flags.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = bearer.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = fields.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = url.replace(safe) {
            val value = userInfo.replace(it.value, "<credential>@")
            value.substringBefore('?').substringBefore('#') +
                if ('?' in value || '#' in value) "?<redacted>" else ""
        }
        return uuid.replace(safe, "<identifier>")
    }
}
