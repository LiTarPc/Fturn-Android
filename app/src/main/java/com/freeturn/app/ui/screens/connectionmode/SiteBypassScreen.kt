@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.connectionmode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.R
import com.freeturn.app.ui.screens.settings.HotspotOptionsCard
import com.freeturn.app.ui.screens.splittunnel.SplitTunnelModal
import androidx.compose.runtime.saveable.rememberSaveable
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.ui.components.*
import com.freeturn.app.ui.theme.Spacing
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Client-facing entry point; independent of diagnostics mode. */
@Composable
fun SiteBypassScreen(settings: SettingsViewModel, proxy: ProxyViewModel,
    serverId: String?, onBack: () -> Unit) {
    val snapshot by settings.serversSnapshot.collectAsStateWithLifecycle()
    val state by proxy.proxyState.collectAsStateWithLifecycle()
    val id = serverId ?: snapshot.activeId
    val server = snapshot.list.firstOrNull { it.id == id }
    var showApps by rememberSaveable(id) { mutableStateOf(false) }
    val locked = server?.id == snapshot.activeId && state !is ProxyState.Idle && state !is ProxyState.Error
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.settings_vpn)) },
                navigationIcon = { SettingsBackButton(onBack) }, scrollBehavior = scroll)
        }, contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = SettingsContentMaxWidth).fillMaxWidth().padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                if (server != null) {
                    SectionLabel(stringResource(R.string.vpn_profile_label, server.name))
                    Text(stringResource(R.string.vpn_profile_desc), style = MaterialTheme.typography.bodyMedium)
                }
                when {
                    server == null -> Text(stringResource(R.string.site_bypass_empty))
                    !server.client.vpnActive -> SettingsCard {
                        SettingsFieldSlot { Text(stringResource(R.string.site_bypass_vpn)) }
                    }
                    else -> {
                        SettingsCard {
                            SettingsEntryRow(iconRes = R.drawable.mobile_24px,
                                title = stringResource(R.string.split_tunnel_title),
                                subtitle = stringResource(when (server.client.splitTunnelMode) {
                                    com.freeturn.app.data.config.SplitTunnelMode.ALL -> R.string.vpn_apps_all
                                    com.freeturn.app.data.config.SplitTunnelMode.INCLUDE -> R.string.vpn_apps_selected
                                    else -> R.string.vpn_apps_except
                                }),
                                onClick = { showApps = true })
                        }
                        BypassRulesCard(
                        config = server.client, profileId = server.id,
                        locked = server.id == snapshot.activeId && state !is ProxyState.Idle && state !is ProxyState.Error,
                        settings = settings,
                        edit = { transform -> settings.updateServerClient(server.id, transform) }
                        )
                    }
                }
                SectionLabel(stringResource(R.string.vpn_global_label))
                HotspotOptionsCard(settings)
            }
        }
    }
    if (showApps && server != null && server.client.vpnActive) {
        SplitTunnelModal(mode = server.client.splitTunnelMode, apps = server.client.splitTunnelApps,
            useDefaults = server.client.splitTunnelUseDefaults, locked = locked,
            onModeChange = { mode -> settings.updateServerClient(server.id) { it.copy(splitTunnelMode = mode) } },
            onAppsChange = { apps -> settings.updateServerClient(server.id) { it.copy(splitTunnelApps = apps, splitTunnelUseDefaults = false) } },
            onDismiss = { showApps = false })
    }

}
