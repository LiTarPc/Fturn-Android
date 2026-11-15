package com.freeturn.app.testing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import com.freeturn.app.domain.proxy.ProxyServiceState

/** Debug-only lifecycle fixture. Accessible only from the app UID (for example adb run-as). */
class ProcessLossReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.freeturn.app.testing.SIMULATE_PROCESS_LOSS" && ProxyServiceState.isRunning.value) {
            // Unlike force-stop, losing a process must allow Android's sticky restart.
            Process.killProcess(Process.myPid())
        }
    }
}
