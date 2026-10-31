package com.freeturn.app.domain.captcha

/** Public presentation metadata deliberately contains no URL or bearer. */
data class CaptchaUiState(
    val phase: Phase = Phase.IDLE,
    val generation: String = "",
    val requestId: String = "",
    val manualOpen: Boolean = false,
    val retryFailed: Boolean = false,
    val hostVersion: Int = 0,
) {
    enum class Phase { IDLE, BROWSER, MANUAL, WAITING_USER, RESUMING }
    val isWaiting: Boolean get() = phase != Phase.IDLE
}
