@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.freeturn.app.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.freeturn.app.R
import com.freeturn.app.ui.components.SettingsBackButton
import com.freeturn.app.ui.screens.clientsetup.ClientSetupScreen
import com.freeturn.app.ui.screens.connectionmode.ConnectionModeScreen
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** One entry for connection settings, keeping TURN and VPN controls distinct. */
@Composable
fun ConnectionSettingsScreen(serverId: String, settings: SettingsViewModel,
    server: ServerViewModel, initialTab: Int = 0,
    onOpenVpn: () -> Unit, onBack: () -> Unit) {
    var selected by rememberSaveable(serverId) { mutableIntStateOf(initialTab) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.connection_parameters)) },
            navigationIcon = { SettingsBackButton(onBack) })
    }, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selected) {
                listOf(R.string.provider_connection_settings, R.string.connection_mode_title).forEachIndexed { index, label ->
                    Tab(selected = selected == index, onClick = { selected = index },
                        text = { Text(stringResource(label)) })
                }
            }
            Box(Modifier.weight(1f)) {
                if (selected == 0) ClientSetupScreen(settings, server, serverId, embedded = true)
                else ConnectionModeScreen(settings, serverId, embedded = true, onOpenVpn = onOpenVpn)
            }
        }
    }
}
