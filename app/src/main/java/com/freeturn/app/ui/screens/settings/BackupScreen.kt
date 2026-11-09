@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.components.BackupPasswordDialog
import com.freeturn.app.ui.components.SectionLabel
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.ui.components.SettingsEntryRow
import com.freeturn.app.ui.components.SettingsGroup
import com.freeturn.app.ui.components.SettingsGroupItem
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(
    settingsViewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    var exportPassword by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var showRestoreDialog by rememberSaveable { mutableStateOf(false) }
    var restoreUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { restoreUri = uri; showRestoreDialog = true }
    }

    // SAF: имя выбирает пользователь, пароль уже введён в диалоге выше.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) settingsViewModel.exportBackup(uri, exportPassword)
        exportPassword = ""
    }

    // Снекбар по результату экспорта/импорта.
    LaunchedEffect(Unit) {
        settingsViewModel.backupEvents.collect { event ->
            snackbarHostState.showSnackbar(backupEventMessage(context, event))
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_section_backup)) },
                navigationIcon = { SettingsBackButton(onBack) },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                    SettingsGroupItem(0, 2) {
                        SettingsEntryRow(
                            iconRes = R.drawable.cloud_download_24px,
                            title = stringResource(R.string.backup_export_title),
                            subtitle = stringResource(R.string.backup_export_desc),
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                showExportDialog = true
                            }
                        )
                    }
                    SettingsGroupItem(1, 2) {
                        SettingsEntryRow(
                            iconRes = R.drawable.cloud_download_24px,
                            title = stringResource(R.string.backup_restore_title),
                            subtitle = stringResource(R.string.add_restore_desc),
                            onClick = { restoreLauncher.launch(arrayOf("*/*")) }
                        )
                    }
                }


            }
        }
    }

    if (showRestoreDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_restore_title),
            confirmLabel = stringResource(R.string.backup_restore_action),
            requireConfirmation = false,
            onConfirm = { password ->
                showRestoreDialog = false
                restoreUri?.let { settingsViewModel.restoreBackup(it, password) }
            },
            onDismiss = { showRestoreDialog = false },
            warning = stringResource(R.string.backup_restore_warning)
        )
    }

    if (showExportDialog) {
        BackupPasswordDialog(
            title = stringResource(R.string.backup_export_title),
            confirmLabel = stringResource(R.string.backup_export_action),
            requireConfirmation = true,
            onConfirm = { password ->
                exportPassword = password
                showExportDialog = false
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                exportLauncher.launch("freeturn-backup-$stamp.ftbackup")
            },
            onDismiss = { showExportDialog = false }
        )
    }
}
