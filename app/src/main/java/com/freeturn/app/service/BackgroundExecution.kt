package com.freeturn.app.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

/** System controls are user decisions; an exemption is not an OEM autostart permission. */
internal object BackgroundExecution {
    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun backgroundRestricted(context: Context): Boolean = Build.VERSION.SDK_INT >= 28 &&
        context.getSystemService(ActivityManager::class.java).isBackgroundRestricted

    fun notificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    val honorOrHuawei: Boolean get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("HONOR", ignoreCase = true) || it.equals("HUAWEI", ignoreCase = true)
    }

    private fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        "package:${context.packageName}".toUri())

    @SuppressLint("BatteryLife") // An active VPN needs network access while the screen is off.
    fun openBatterySettings(context: Context): Boolean {
        val intents = buildList {
            if (!batteryExempt(context)) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri()))
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(appDetails(context))
        }
        return open(context, intents)
    }

    fun openAppSettings(context: Context) = open(context, listOf(appDetails(context)))

    fun openNotificationSettings(context: Context): Boolean = open(context, buildList {
        if (Build.VERSION.SDK_INT >= 26) add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        add(appDetails(context))
    })

    private fun open(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Some OEM builds omit the standard battery activity. Try the next public screen.
            } catch (_: SecurityException) {
                // A vendor may restrict a settings activity even when it resolves.
            }
        }
        return false
    }
}
