package com.freeturn.app.service

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.freeturn.app.domain.diagnostics.DiagnosticLogs

/** Log system evidence on next launch, without collecting traces or restarting a stopped VPN. */
internal object ProcessExitDiagnostics {
    fun record(context: Context) {
        runCatching {
            val power = context.getSystemService(PowerManager::class.java)
            DiagnosticLogs.append("Android background: batteryExempt=${BackgroundExecution.batteryExempt(context)}, " +
                "restricted=${BackgroundExecution.backgroundRestricted(context)}, " +
                "powerSave=${power.isPowerSaveMode}, notifications=${BackgroundExecution.notificationsEnabled(context)}")
            if (Build.VERSION.SDK_INT >= 30) {
                val activity = context.getSystemService(ActivityManager::class.java)
                // Native child processes can exit independently; report the Android app process.
                val previous = activity.getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .firstOrNull { it.processName == context.applicationInfo.processName }
                if (previous != null) {
                    val reason = when (previous.reason) {
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                        ApplicationExitInfo.REASON_CRASH -> "java_crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                        ApplicationExitInfo.REASON_ANR -> "anr"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "user_requested"
                        ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
                        ApplicationExitInfo.REASON_SIGNALED -> "signal"
                        ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                        else -> "other"
                    }
                    // Descriptions and traces may contain private application data; omit them.
                    DiagnosticLogs.append("Android previous app exit: reason=$reason (${previous.reason}), " +
                        "timestamp=${previous.timestamp}, status=${previous.status}, importance=${previous.importance}, " +
                        "pssKiB=${previous.pss}, rssKiB=${previous.rss}")
                }
            }
        }.onFailure {
            DiagnosticLogs.append("Android process exit diagnostics unavailable: ${it.javaClass.simpleName}")
        }
    }
}
