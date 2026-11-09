package com.freeturn.app.testing

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.freeturn.app.service.CaptchaCoordinator
import com.freeturn.app.service.ProxyNotifier

/** Isolated debug-only harness. This service never starts a core or a VPN. */
class CaptchaFixtureService : Service() {
    lateinit var coordinator: CaptchaCoordinator
        private set
    private lateinit var notifier: ProxyNotifier
    private var wakeLock: PowerManager.WakeLock? = null
    inner class LocalBinder : Binder() { val service get() = this@CaptchaFixtureService }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        notifier = ProxyNotifier(this)
        notifier.createChannels(); notifier.prepareConnecting()
        coordinator = CaptchaCoordinator(applicationContext, notifier::updateCaptchaState, pagePort = null)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ProxyNotifier.NOTIF_ID_FG, notifier.build(), type)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fturn::CaptchaFixture")
            .also { it.acquire(180_000) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onDestroy() {
        coordinator.close(); notifier.cancelCaptcha()
        wakeLock?.let { if (it.isHeld) it.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
