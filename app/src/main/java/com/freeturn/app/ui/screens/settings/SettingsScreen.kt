@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import android.widget.Toast
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.util.rememberJournalExport
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsCard
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.components.SettingsSwitchRow
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import com.freeturn.app.ui.theme.Spacing

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

/** Корневой экран настроек (нижнее меню): серверы, VPN, приложение, о приложении. */
@Composable
fun SettingsScreen(
    onOpenServers: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBypass: () -> Unit
) {
    val export = rememberJournalExport()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior
            )
        },
        // Корневой экран - внутри NavigationSuite, нижний бар сам держит инсет.
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
                SettingsGroup {
                    SettingsGroupItem(0, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.database_24px,
                            title = stringResource(R.string.settings_servers),
                            subtitle = stringResource(R.string.settings_servers_desc),
                            onClick = onOpenServers
                        )
                    }
                    SettingsGroupItem(1, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.public_24px,
                            title = stringResource(R.string.settings_vpn),
                            subtitle = stringResource(R.string.settings_vpn_desc),
                            onClick = onOpenBypass
                        )
                    }
                    SettingsGroupItem(2, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.mobile_24px,
                            title = stringResource(R.string.settings_app),
                            subtitle = stringResource(R.string.settings_app_desc),
                            onClick = onOpenApp
                        )
                    }
                    SettingsGroupItem(3, 4) {
                        SettingsEntryRow(
                            iconRes = R.drawable.info_24px,
                            title = stringResource(R.string.settings_about),
                            subtitle = stringResource(R.string.settings_about_desc),
                            onClick = onOpenAbout
                        )
                    }
                }
                SectionLabel(stringResource(R.string.support_title))
                SettingsGroup {
                    SettingsGroupItem(0, 2) {
                        SettingsEntryRow(
                            iconRes = R.drawable.description_24px,
                            title = stringResource(if (export.busy) R.string.logs_export_busy else R.string.logs_save_journal),
                            subtitle = stringResource(R.string.logs_save_journal_desc),
                            onClick = export.save
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        SettingsEntryRow(
                            iconRes = R.drawable.share_24px,
                            title = stringResource(R.string.support_send_logs),
                            subtitle = stringResource(R.string.support_send_logs_desc),
                            trailingRes = R.drawable.open_in_new_24px,
                            onClick = {
                                try { uriHandler.openUri("https://t.me/FturnSupport") }
                                catch (_: Exception) {
                                    Toast.makeText(context, R.string.support_open_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** "Диагностика": переключатель "Режим отладки" ([SettingsViewModel.nerdMode]). */
@Composable
fun AdvancedScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val restartServerOnSwitch by settingsViewModel.restartServerOnSwitch.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var isPasswordError by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_advanced)) },
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
                SettingsCard {
                    SettingsSwitchRow(
                        title = stringResource(R.string.nerd_mode),
                        subtitle = stringResource(R.string.nerd_mode_desc),
                        iconRes = R.drawable.terminal_24px,
                        checked = nerdMode,
                        onCheckedChange = { targetChecked ->
                            if (targetChecked) {
                                passwordInput = ""
                                isPasswordError = false
                                showPasswordDialog = true
                            } else {
                                settingsViewModel.setNerdMode(false)
                            }
                        }
                    )
                }
                if (nerdMode) {
                    SettingsCard {
                        SettingsSwitchRow(
                            title = stringResource(R.string.restart_server_on_switch),
                            subtitle = stringResource(R.string.restart_server_on_switch_desc),
                            iconRes = R.drawable.restart_alt_24px,
                            checked = restartServerOnSwitch,
                            onCheckedChange = { settingsViewModel.setRestartServerOnSwitch(it) }
                        )
                    }
                }
            }
        }
    }

    if (showPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Режим отладки") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Введите пароль для активации режима разработчика:")
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            isPasswordError = false
                        },
                        singleLine = true,
                        isError = isPasswordError,
                        supportingText = if (isPasswordError) {
                            { Text("Неверный пароль", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (passwordInput == "admin123") {
                            settingsViewModel.setNerdMode(true)
                            showPasswordDialog = false
                        } else {
                            isPasswordError = true
                        }
                    }
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasswordDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}
