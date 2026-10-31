package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyServiceState

import android.app.Notification
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.freeturn.app.domain.proxy.DnsFallback
import com.freeturn.app.domain.proxy.DnsFallbackAlerts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.freeturn.app.R
import java.util.Locale

/**
 * Нотификации прокси-сервиса: foreground-статус (+скорость/число потоков) и
 * отдельный алерт ручной капчи. Статус и скорость держит внутри, перерисовывает
 * сам. Скорость/счётчик показываются только при "активных" статусах.
 */
class ProxyNotifier(private val service: Service) {

    companion object {
        const val NOTIF_ID_FG = 1
        private const val NOTIF_ID_CAPTCHA = 2
        private const val NOTIF_ID_DNS = 3
        private const val CHANNEL_PROXY = "ProxyChannel"
        private const val CHANNEL_CAPTCHA = "CaptchaChannel"
        private const val CHANNEL_DNS = "DnsFallbackChannel"
    }

    private val openAppIntent: PendingIntent? by lazy {
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let {
            PendingIntent.getActivity(service, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
    }

    private val dnsAlerts = DnsFallbackAlerts()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var baseStatus = ""
    private var speedText = ""
    private var captchaActive = false
    private var captchaKey = ""
    private var authState = com.freeturn.app.domain.captcha.CaptchaUiState()
    private var isActive = false

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = service.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROXY,
                service.getString(R.string.notif_channel_proxy),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CAPTCHA,
                service.getString(R.string.notif_channel_captcha),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DNS, service.getString(R.string.notif_channel_dns),
                NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) }
        )
    }

    /** Базовый статус "Подключение..." для самого первого startForeground. */
    fun prepareConnecting() {
        baseStatus = service.getString(R.string.notif_proxy_connecting)
        isActive = false
    }

    /** [active] - соединение установлено (прокси или прокси+WG): показываем потоки и скорость. */
    fun setStatus(text: String, active: Boolean = false) {
        baseStatus = text
        isActive = active
        show()
    }

    fun setSpeed(text: String) {
        speedText = text
        if (isActive) show()
    }

    /** Перерисовать (обновился счётчик потоков) - только если статус активен. */
    fun refreshStats() {
        if (isActive) show()
    }

    fun build(): Notification {
        val stats = ProxyServiceState.connectionStats.value
        // Активное соединение: статус уезжает в заголовок, строка текста целиком под
        // потоки и скорость - вместе они в одну строку не влезают.
        val title: String
        val text: String
        if (isActive) {
            title = baseStatus
            text = listOfNotNull(
                if (stats.total > 0) String.format(
                    Locale.US,
                    service.getString(R.string.notif_proxy_threads_format),
                    stats.active,
                    stats.total
                ) else null,
                authStatus().takeIf { it.isNotEmpty() },
                speedText.takeIf { it.isNotEmpty() }
            ).joinToString(" • ")
        } else {
            title = service.getString(R.string.notif_proxy_title)
            text = authStatus().ifEmpty { baseStatus }
        }

        val stopIntent = Intent(service, ProxyReceiver::class.java).apply {
            action = ProxyActions.STOP
        }
        val stopPending = PendingIntent.getBroadcast(
            service, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(service, CHANNEL_PROXY)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_preferences)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, service.getString(R.string.notif_proxy_stop_action), stopPending)

        val connectedSince = ProxyServiceState.connectedSince.value
        if (isActive && connectedSince != null) {
            val baseTime = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedSince)
            builder.setUsesChronometer(true)
            builder.setWhen(baseTime)
            builder.setShowWhen(true)
        }

        return builder.build()
    }

    private fun show() {
        try {
            NotificationManagerCompat.from(service).notify(NOTIF_ID_FG, build())
        } catch (_: SecurityException) {}
    }

    /** Показ алерта капчи. Дедуп: пока предыдущий не закрыт - повторно не шумим. */
    fun showCaptcha() {
        if (captchaActive) return
        captchaActive = true
        val notification = NotificationCompat.Builder(service, CHANNEL_CAPTCHA)
            .setContentTitle(service.getString(R.string.notif_captcha_title))
            .setContentText(service.getString(R.string.notif_captcha_text))
            .setSmallIcon(R.drawable.ic_notification_captcha)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        try {
            NotificationManagerCompat.from(service).notify(NOTIF_ID_CAPTCHA, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS отозван на API 33+ - молча игнорируем, диалог в UI
            // всё равно откроется через captchaSession StateFlow.
        }
    }

    private fun authStatus(): String = when (authState.phase) {
        com.freeturn.app.domain.captcha.CaptchaUiState.Phase.IDLE -> ""
        com.freeturn.app.domain.captcha.CaptchaUiState.Phase.BROWSER -> service.getString(R.string.auth_browser_title)
        com.freeturn.app.domain.captcha.CaptchaUiState.Phase.MANUAL -> service.getString(R.string.auth_manual_title)
        com.freeturn.app.domain.captcha.CaptchaUiState.Phase.WAITING_USER -> service.getString(R.string.auth_waiting_title)
        com.freeturn.app.domain.captcha.CaptchaUiState.Phase.RESUMING -> service.getString(R.string.auth_resuming_title)
    }

    fun updateCaptchaState(value: com.freeturn.app.domain.captcha.CaptchaUiState) {
        authState = value
        val needsUser = value.phase in setOf(com.freeturn.app.domain.captcha.CaptchaUiState.Phase.MANUAL,
            com.freeturn.app.domain.captcha.CaptchaUiState.Phase.WAITING_USER)
        if (!needsUser) cancelCaptcha()
        else {
            val key = "${value.generation}:${value.requestId}:${value.phase}"
            if (captchaKey != key) {
                captchaKey = key
                captchaActive = true
                val notification = NotificationCompat.Builder(service, CHANNEL_CAPTCHA)
                    .setContentTitle(service.getString(R.string.notif_captcha_title))
                    .setContentText(authStatus())
                    .setSmallIcon(R.drawable.ic_notification_captcha)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOnlyAlertOnce(true).setAutoCancel(false).setContentIntent(openAppIntent)
                if (value.phase == com.freeturn.app.domain.captcha.CaptchaUiState.Phase.WAITING_USER) {
                    val retry = Intent(service, ProxyReceiver::class.java).setAction(ProxyActions.CAPTCHA_RESUME)
                        .putExtra(ProxyActions.CAPTCHA_GENERATION, value.generation)
                    val pending = PendingIntent.getBroadcast(service, 437, retry,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    notification.addAction(0, service.getString(R.string.auth_retry), pending)
                }
                try { NotificationManagerCompat.from(service).notify(NOTIF_ID_CAPTCHA, notification.build()) }
                catch (_: SecurityException) { /* The same action remains available in the app. */ }
            }
        }
        show()
    }

    fun cancelCaptcha() {
        NotificationManagerCompat.from(service).cancel(NOTIF_ID_CAPTCHA)
        captchaActive = false
        captchaKey = ""
    }
    fun resetDnsAlerts() {
        dnsAlerts.reset()
        cancelDnsAlert()
    }

    fun showDnsFallback(fallback: DnsFallback, carrierSelected: Boolean = false) {
        if (!dnsAlerts.mark(fallback)) return
        val text = service.getString(when (fallback) {
            DnsFallback.CARRIER_UNAVAILABLE -> R.string.notif_dns_carrier_missing
            DnsFallback.DOH -> if (carrierSelected) R.string.notif_dns_carrier_doh else R.string.notif_dns_doh
        })
        val process = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(process)
        val foreground = process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        if (foreground) mainHandler.post { Toast.makeText(service, text, Toast.LENGTH_LONG).show() }
        val notification = NotificationCompat.Builder(service, CHANNEL_DNS)
            .setContentTitle(service.getString(R.string.notif_dns_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setSilent(foreground)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()
        try {
            NotificationManagerCompat.from(service).notify(NOTIF_ID_DNS, notification)
        } catch (_: SecurityException) {
            // The foreground toast remains visible when notification permission is denied.
        }
    }

    fun cancelDnsAlert() {
        NotificationManagerCompat.from(service).cancel(NOTIF_ID_DNS)
    }

}
