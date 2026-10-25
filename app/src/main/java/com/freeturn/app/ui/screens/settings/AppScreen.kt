@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.domain.UpdateState
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.BusyProgressIndicator
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsRowIcon
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.ui.util.hapticClickable
import com.freeturn.app.viewmodel.settings.BackupEvent
import com.freeturn.app.viewmodel.settings.RestoreFailReason
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** "Приложение": оформление, резервные копии, диагностика и сброс. */
@Suppress("AssignedValueIsNeverRead") // showResetDialog пишется в лямбдах диалога
@Composable
fun AppScreen(
    settingsViewModel: SettingsViewModel,
    onOpenBackup: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBack: () -> Unit
) {
    val appTheme by settingsViewModel.appTheme.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_app)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                SectionLabel(stringResource(R.string.app_section_interface))
                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.privacy_mode_title),
                            subtitle = stringResource(R.string.privacy_mode_desc),
                            iconRes = R.drawable.visibility_off_24px,
                            checked = privacyMode,
                            onCheckedChange = { settingsViewModel.setPrivacyMode(it) }
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        com.freeturn.app.ui.components.SettingsFieldSlot {
                            com.freeturn.app.ui.components.SettingsControlLabel(
                                title = stringResource(R.string.wine_theme_title),
                                desc = stringResource(R.string.wine_theme_desc)
                            )
                            androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                com.freeturn.app.data.config.AppTheme.VALUES.forEachIndexed { index, mode ->
                                    SegmentedButton(
                                        selected = appTheme == mode,
                                        onClick = { settingsViewModel.setAppTheme(mode) },
                                        shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(index, 3)
                                    ) {
                                        Text(stringResource(when (mode) {
                                            com.freeturn.app.data.config.AppTheme.DARK -> R.string.theme_dark
                                            com.freeturn.app.data.config.AppTheme.SYSTEM -> R.string.theme_system
                                            else -> R.string.theme_light
                                        }), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsEntryRow(iconRes = R.drawable.cloud_download_24px,
                            title = stringResource(R.string.app_section_backup),
                            subtitle = stringResource(R.string.backup_actions_desc), onClick = onOpenBackup)
                    }
                    SettingsGroupItem(1, 2) {
                        SettingsEntryRow(iconRes = R.drawable.terminal_24px,
                            title = stringResource(R.string.settings_advanced),
                            subtitle = stringResource(R.string.nerd_mode), onClick = onOpenDiagnostics)
                    }
                }

                SectionLabel(stringResource(R.string.app_section_reset))
                SettingsCard {
                    ResetRow(onClick = { showResetDialog = true })
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(stringResource(R.string.reset_all_settings_title)) },
            text = { Text(stringResource(R.string.reset_all_settings_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        settingsViewModel.resetAllSettings()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }


}

/** Текст снекбара по результату экспорта/восстановления (строки выбирает UI, не ViewModel). */
internal fun backupEventMessage(context: android.content.Context, event: BackupEvent): String =
    when (event) {
        BackupEvent.ExportSuccess -> context.getString(R.string.backup_export_ok)
        BackupEvent.ExportFailed -> context.getString(R.string.backup_export_fail)
        is BackupEvent.RestoreSuccess -> context.getString(R.string.backup_restore_ok, event.count)
        is BackupEvent.RestoreFailed -> when (event.reason) {
            RestoreFailReason.BAD_PASSWORD -> context.getString(R.string.backup_restore_bad_password)
            RestoreFailReason.BAD_FILE -> context.getString(R.string.backup_restore_bad_file)
            RestoreFailReason.IO -> context.getString(R.string.backup_restore_fail)
        }
    }

/**
 * Карточка обновления. Двухэтажная: шапка (иконка, заголовок, статус), под ней действие
 * во всю ширину - кнопка по состоянию либо прогресс загрузки. В одну строку не влезает на узких экранах.
 */
@Composable
internal fun UpdateCard(
    state: UpdateState,
    appVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val statusText = when (state) {
        is UpdateState.Idle -> stringResource(R.string.update_current_version, "v$appVersion")
        is UpdateState.Checking -> stringResource(R.string.update_checking)
        is UpdateState.Available -> stringResource(R.string.update_available, state.version)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, state.progress) + if (state.viaVpn) " • VPN" else ""
        is UpdateState.Verifying -> stringResource(R.string.update_verifying)
        is UpdateState.InstallPermissionRequired -> stringResource(R.string.update_install_permission_title)
        is UpdateState.ReadyToInstall -> stringResource(R.string.update_ready_desc_short)
        is UpdateState.NoUpdate -> stringResource(R.string.update_no_update)
        is UpdateState.Error -> stringResource(R.string.update_error, state.message)
    }

    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                modifier = Modifier.fillMaxWidth()
            ) {
                SettingsRowIcon(R.drawable.cloud_download_24px)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_title), style = MaterialTheme.typography.bodyLarge)
                    AnimatedContent(
                        targetState = statusText,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "update_status"
                    ) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state is UpdateState.Error) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (state is UpdateState.Downloading) {
                BusyProgressIndicator(progress = { state.progress / 100f })
            } else {
                val action = when (state) {
                    is UpdateState.Available -> onDownload
                    is UpdateState.ReadyToInstall, is UpdateState.InstallPermissionRequired -> onInstall
                    else -> onCheck
                }
                FilledTonalButton(
                    onClick = action,
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Verifying,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state is UpdateState.Checking || state is UpdateState.Verifying) {
                        LoadingIndicator(modifier = Modifier.size(22.dp))
                    } else {
                        Text(
                            when (state) {
                                is UpdateState.Available -> stringResource(R.string.update_download)
                                is UpdateState.ReadyToInstall, is UpdateState.InstallPermissionRequired -> stringResource(R.string.update_install)
                                else -> stringResource(R.string.update_check)
                            },
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** Строка сброса: error-тинт иконки и заголовка, без trailing-шеврона. */
@Composable
private fun ResetRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(HapticUtil.Pattern.CLICK, onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        SettingsRowIcon(
            R.drawable.delete_24px,
            container = MaterialTheme.colorScheme.errorContainer,
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.reset_settings),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                stringResource(R.string.app_reset_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
