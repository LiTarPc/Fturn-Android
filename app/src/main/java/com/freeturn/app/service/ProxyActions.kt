package com.freeturn.app.service

/** Action-строки управления [ProxyService] через [ProxyReceiver] (тайл/нотификация). */
object ProxyActions {
    const val START = "com.freeturn.app.START_PROXY"
    const val CAPTCHA_RESUME = "com.freeturn.app.RESUME_CAPTCHA"
    const val CAPTCHA_GENERATION = "captcha_generation"
    const val STOP = "com.freeturn.app.STOP_PROXY"
}
