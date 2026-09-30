package com.freeturn.app.domain.proxy

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.service.SingBoxVpnService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class SingBoxTunnelManager(context: Context) {
    private val context = context.applicationContext
    private val mutex = Mutex()
    private var connection: ServiceConnection? = null
    private var service: SingBoxVpnService? = null

    suspend fun startAfterProxyReady(cfg: ClientConfig) = mutex.withLock {
        if (!cfg.vpnActive) return@withLock
        stopLocked()
        val ready = CompletableDeferred<SingBoxVpnService>()
        val intent = Intent(context, SingBoxVpnService::class.java)
        val binding = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete((binder as SingBoxVpnService.LocalBinder).service)
            }
            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("VPN отключён"))
                ProxyServiceState.setTunnelActive(false)
                context.stopService(Intent(context, com.freeturn.app.service.ProxyService::class.java))
            }
        }
        try {
            withContext(Dispatchers.Main) {
                ContextCompat.startForegroundService(context, intent)
                check(context.bindService(intent, binding, Context.BIND_AUTO_CREATE)) { "Не удалось подключить VPN-сервис" }
                connection = binding
            }
            service = withTimeout(15_000) { ready.await() }
            withContext(Dispatchers.IO) { service!!.startTunnel(cfg) }
        } catch (e: Exception) {
            stopLocked()
            throw e
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() = withContext(NonCancellable) {
        try { withContext(Dispatchers.IO) { service?.stopTunnel() } } finally {
            service = null
            withContext(Dispatchers.Main) {
                connection?.let { context.unbindService(it) }
                connection = null
                context.stopService(Intent(context, SingBoxVpnService::class.java))
            }
        }
    }
}
