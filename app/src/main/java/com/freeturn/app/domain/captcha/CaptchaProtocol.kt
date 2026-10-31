package com.freeturn.app.domain.captcha

import org.json.JSONObject

/** Versioned, non-secret stdout metadata. Challenge URLs are fetched separately. */
object CaptchaProtocol {
    const val PREFIX = "FTURN_EVENT "
    private val id = Regex("[0-9a-f]{32}")
    enum class Mode { BROWSER, MANUAL }
    enum class Phase { PENDING, RESOLVED, EXPIRED, CANCELLED, UNAVAILABLE }
    enum class AuthPhase { WAITING_USER, RESUMING, AUTHORIZED }
    sealed interface Event { val generation: String; val ipcPort: Int }
    data class Challenge(
        override val generation: String, override val ipcPort: Int,
        val requestId: String, val mode: Mode, val phase: Phase, val expiresAt: Long,
    ) : Event
    data class Auth(override val generation: String, override val ipcPort: Int, val phase: AuthPhase) : Event
    sealed interface Result {
        data object NotEvent : Result
        data class Accepted(val event: Event) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(line: String, generation: String, now: Long): Result {
        if (!line.startsWith(PREFIX)) return Result.NotEvent
        if (line.length > 4096) return Result.Rejected("oversized")
        return try {
            val json = JSONObject(line.removePrefix(PREFIX))
            if (json.getInt("v") != 1) return Result.Rejected("version")
            if (json.getString("generation") != generation) return Result.Rejected("generation")
            val port = json.getInt("ipc_port")
            if (port !in 1..65535) return Result.Rejected("endpoint")
            when (json.getString("type")) {
                "captcha" -> {
                    val requestId = json.getString("request_id")
                    if (!id.matches(requestId)) return Result.Rejected("identifier")
                    val mode = Mode.valueOf(json.getString("mode").uppercase())
                    val phase = Phase.valueOf(json.getString("phase").uppercase())
                    val expiresAt = json.getLong("expires_at_ms")
                    if (phase == Phase.PENDING && (expiresAt <= now || expiresAt - now > 600_000)) {
                        return Result.Rejected("expiry")
                    }
                    Result.Accepted(Challenge(generation, port, requestId, mode, phase, expiresAt))
                }
                "auth" -> Result.Accepted(Auth(generation, port,
                    AuthPhase.valueOf(json.getString("phase").uppercase())))
                else -> Result.Rejected("type")
            }
        } catch (_: Exception) { Result.Rejected("schema") }
    }
}
