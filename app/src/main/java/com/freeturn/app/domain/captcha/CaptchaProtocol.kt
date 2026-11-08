package com.freeturn.app.domain.captcha

import java.util.Locale
import org.json.JSONObject

/** Versioned, non-secret stdout metadata. Challenge URLs are fetched separately. */
object CaptchaProtocol {
    const val PREFIX = "FTURN_EVENT "
    private val id = Regex("[0-9a-f]{32}")
    enum class Mode { BROWSER, MANUAL }
    enum class Purpose { POW_CAPTURE, SOLVE }
    enum class Phase { PENDING, CAPTURED, RESOLVED, EXPIRED, CANCELLED, UNAVAILABLE }
    enum class AuthPhase { WAITING_USER, RESUMING, AUTHORIZED }
    sealed interface Event { val generation: String; val ipcPort: Int; val version: Int }
    data class Challenge(
        override val generation: String, override val ipcPort: Int,
        val requestId: String, val mode: Mode, val phase: Phase, val expiresAt: Long,
        val purpose: Purpose = Purpose.SOLVE, override val version: Int = 1,
    ) : Event
    data class Auth(override val generation: String, override val ipcPort: Int, val phase: AuthPhase,
        override val version: Int = 1) : Event
    sealed interface Result {
        data object NotEvent : Result
        data class Accepted(val event: Event) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(line: String, generation: String, now: Long, expectedVersion: Int? = null): Result {
        if (!line.startsWith(PREFIX)) return Result.NotEvent
        if (line.length > 4096) return Result.Rejected("oversized")
        return try {
            val json = JSONObject(line.removePrefix(PREFIX))
            if (json.getString("generation") != generation) return Result.Rejected("generation")
            val version = json.getInt("v")
            if (version !in 1..2 || (expectedVersion != null && version != expectedVersion)) return Result.Rejected("version")
            val port = json.getInt("ipc_port")
            if (port !in 1..65535) return Result.Rejected("endpoint")
            when (json.getString("type")) {
                "captcha" -> {
                    val requestId = json.getString("request_id")
                    if (!id.matches(requestId)) return Result.Rejected("identifier")
                    val mode = Mode.valueOf(json.getString("mode").uppercase(Locale.ROOT))
                    val phase = Phase.valueOf(json.getString("phase").uppercase(Locale.ROOT))
                    val purpose = if (version == 2) Purpose.valueOf(json.getString("purpose").uppercase(Locale.ROOT)) else Purpose.SOLVE
                    if (version == 1 && (json.has("purpose") || phase == Phase.CAPTURED)) return Result.Rejected("purpose")
                    if (purpose == Purpose.POW_CAPTURE && (mode != Mode.BROWSER || phase == Phase.RESOLVED)) return Result.Rejected("purpose")
                    if (purpose == Purpose.SOLVE && phase == Phase.CAPTURED) return Result.Rejected("purpose")
                    val expiresAt = json.getLong("expires_at_ms")
                    val maxTtl = if (purpose == Purpose.POW_CAPTURE) 12_000L else 600_000L
                    if (phase == Phase.PENDING && (expiresAt <= now || expiresAt - now > maxTtl)) return Result.Rejected("expiry")
                    Result.Accepted(Challenge(generation, port, requestId, mode, phase, expiresAt, purpose, version))
                }
                "auth" -> Result.Accepted(Auth(generation, port,
                    AuthPhase.valueOf(json.getString("phase").uppercase(Locale.ROOT)), version))
                else -> Result.Rejected("type")
            }
        } catch (_: Exception) { Result.Rejected("schema") }
    }
}
