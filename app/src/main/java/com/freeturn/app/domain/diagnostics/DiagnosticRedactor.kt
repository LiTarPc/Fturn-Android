package com.freeturn.app.domain.diagnostics

/** Applies to persisted, exported and displayed logs, regardless of the UI privacy switch. */
object DiagnosticRedactor {
    private val ansi = Regex("\\x1b\\[[0-?]*[ -/]*[@-~]")
    private val profile = Regex("(?i)\\b(?:fturnraw|freeturn|vless|vmess|ss|trojan|wireguard)://\\S+")
    private val call = Regex("(?i)https?://(?:vk\\.ru|vk\\.com)/call/join/[^\\s\"']+")
    private val flags = Regex("(?i)(?<![a-z0-9_-])(--?(?:obf-key|raw-key|private-key|preshared-key|password|passwd|token|access-token|secret|client-id|links?)(?:=|\\s+))(\"[^\"]*\"|'[^']*'|\\S+)")
    private const val sensitiveNames = """(?:access[_-]?token|refresh[_-]?token|id[_-]?token|success[_-]?token|session[_-]?(?:token|id|key)|csrf[_-]?token|xsrf[_-]?token|authorization|proxy[_-]authorization|password|passwd|secret|private[_-]?key|preshared[_-]?key|obf[_-]?key|raw[_-]?key|turn[_-]?(?:user(?:name)?|password|credential)|username|client[_-]?id|captcha[_-]?(?:sid|key|token)|token|credential|nonce|key)"""
    private val fieldHints = listOf("link", "key", "token", "session", "pass", "secret", "authorization", "cookie", "username", "client", "credential", "nonce", "request_params", "captcha")
    private val fieldPrefix = """((?<![a-z0-9_])["']?$sensitiveNames["']?\s*[:=]\s*)"""
    private val fields = Regex("""(?i)$fieldPrefix("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[^"'\s,;}\]]+)""")
    // Escaped JSON and broken quoted values cannot safely be split into fields.
    // Keep the operation/timestamp prefix and omit the rest of that line.
    private val escapedFields = Regex("""(?im)(?:\\+["'])$sensitiveNames(?:\\+["'])\s*[:=][^\r\n]*""")
    private val incompleteFields = Regex("""(?im)$fieldPrefix["'][^\r\n]*""")
    private val headers = Regex("""(?im)((?<![a-z0-9_-])["']?(?:cookie|set-cookie|authorization|proxy-authorization)["']?\s*[:=]\s*)[^\r\n]*""")
    private val requestParams = Regex("(?im)request_params[^\\r\\n]*")
    private val url = Regex("(?i)https?://[^\\s\"'<>]+")
    private val userInfo = Regex("(?<=://)[^/\\s@]+@")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\b")
    private val captchaUrl = Regex("""(?i)https?://[^\s"'<>]*(?:captcha|not_robot)[^\s"'<>]*""")
    private val localUrl = Regex("""(?i)http://(?:127\.0\.0\.1|localhost)(?::[0-9]+)?[^\s"'<>]*""")
    private val captchaBody = Regex("""(?im)(\[Captcha[^\]]*][^\r\n]*?(?:\bdata\s*:|\bpow\s+envelope\s*:|\breal\s+browser\s+pow\s*:))[^\r\n]*""")

    /** Kept for callers migrating historical captcha bodies. Full exports use [redact]. */
    fun redactLegacyCaptchaPayload(message: String): String =
        captchaBody.replace(message) { it.groupValues[1] + " <redacted>" }

    fun redact(message: String, secrets: List<String> = emptyList()): String {
        if (message.length > 64 * 1024) return "[oversized log entry omitted: ${message.length} characters]"
        var safe = if ('\u001b' in message) ansi.replace(message, "") else message
        secrets.filter { it.length >= 4 }.forEach { safe = safe.replace(it, "<credential>") }
        val lower = safe.lowercase(java.util.Locale.ROOT)
        // Most network records have no authentication fields. Avoid expensive regex
        // passes over large legacy payloads while retaining registered-secret masking.
        if ("://" !in lower && fieldHints.none { it in lower }) return redactIdentifiers(safe)
        safe = redactLegacyCaptchaPayload(safe)
        safe = escapedFields.replace(safe, "<redacted>")
        safe = headers.replace(safe) { it.groupValues[1] + "<credential>" }
        // Request parameter dumps contain authentication data outside named fields.
        safe = requestParams.replace(safe, "request_params=<redacted>")
        safe = profile.replace(safe, "<profile-link>")
        safe = call.replace(safe, "<VK-call>")
        safe = flags.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = fields.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = incompleteFields.replace(safe) { it.groupValues[1] + "<credential>" }
        safe = captchaUrl.replace(safe, "<captcha-url>")
        if (safe.contains("captcha", ignoreCase = true)) safe = localUrl.replace(safe, "<local-captcha>")
        safe = url.replace(safe) {
            val value = userInfo.replace(it.value, "<credential>@")
            value.substringBefore('?').substringBefore('#') +
                if ('?' in value || '#' in value) "?<redacted>" else ""
        }
        return redactIdentifiers(safe)
    }

    private fun redactIdentifiers(text: String): String =
        if (text.length >= 36 && text.count { it == '-' } >= 4) uuid.replace(text, "<identifier>") else text
}
