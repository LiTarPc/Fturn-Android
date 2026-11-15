package com.freeturn.app.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.freeturn.app.R
import com.freeturn.app.service.BackgroundExecution
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.theme.Spacing

@Composable
internal fun BackgroundSettingsCard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var exempt by remember { mutableStateOf(BackgroundExecution.batteryExempt(context)) }
    var restricted by remember { mutableStateOf(BackgroundExecution.backgroundRestricted(context)) }
    var notifications by remember { mutableStateOf(BackgroundExecution.notificationsEnabled(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                exempt = BackgroundExecution.batteryExempt(context)
                restricted = BackgroundExecution.backgroundRestricted(context)
                notifications = BackgroundExecution.notificationsEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun open(action: () -> Boolean) {
        if (!action()) Toast.makeText(context, R.string.background_settings_unavailable, Toast.LENGTH_LONG).show()
    }
    SectionLabel(stringResource(R.string.background_title))
    SettingsCard {
        SettingsEntryRow(iconRes = R.drawable.power_24px,
            title = stringResource(R.string.background_battery_title),
            subtitle = stringResource(if (exempt) R.string.background_battery_exempt else R.string.background_battery_limited),
            onClick = { open { BackgroundExecution.openBatterySettings(context) } })
        SettingsEntryRow(iconRes = R.drawable.settings_24px,
            title = stringResource(R.string.background_app_title),
            subtitle = stringResource(if (restricted) R.string.background_app_restricted else R.string.background_app_desc),
            onClick = { open { BackgroundExecution.openAppSettings(context) } })
        if (!notifications) {
            SettingsEntryRow(iconRes = R.drawable.info_24px,
                title = stringResource(R.string.background_notifications_title),
                subtitle = stringResource(R.string.background_notifications_desc),
                onClick = { open { BackgroundExecution.openNotificationSettings(context) } })
        }
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (BackgroundExecution.honorOrHuawei) {
                Text(stringResource(R.string.background_honor_help), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.background_stop_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
