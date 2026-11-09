package com.freeturn.app.service
import com.freeturn.app.domain.proxy.ProxyServiceState

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ProxyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ProxyActions.START -> {
                ProxyServiceState.clearLogs()
                ProxyServiceState.setStartupResult(null)
                val serviceIntent = Intent(context, ProxyService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
            ProxyActions.CAPTCHA_RESUME -> {
                if (ProxyServiceState.isRunning.value) context.startService(Intent(context, ProxyService::class.java)
                    .setAction(ProxyActions.CAPTCHA_RESUME)
                    .putExtra(ProxyActions.CAPTCHA_GENERATION, intent.getStringExtra(ProxyActions.CAPTCHA_GENERATION)))
            }
            ProxyActions.STOP -> {
                val serviceIntent = Intent(context, ProxyService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}
