@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class
)

package com.freeturn.app.ui.screens.home

import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import com.freeturn.app.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.ui.components.SettingsContentMaxWidth
import com.freeturn.app.data.HapticUtil
import com.freeturn.app.ui.screens.splittunnel.SplitTunnelModal
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel
import kotlinx.coroutines.launch
import com.freeturn.app.ui.theme.Spacing

/** Главный экран (собирает состояния, держит системные ланчеры и чистые компоненты). */
@Composable
fun HomeScreen(
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    onOpenServerSettings: (String) -> Unit,
    onAddServer: () -> Unit
) {
    val context = LocalContext.current
    val proxyState by proxyViewModel.proxyState.collectAsStateWithLifecycle()
    val connectedSince by proxyViewModel.connectedSince.collectAsStateWithLifecycle()
    val uptimeText = rememberProxyUptime(connectedSince)
    val tunnelActive by proxyViewModel.tunnelActive.collectAsStateWithLifecycle()
    val clientConfig by settingsViewModel.clientConfig.collectAsStateWithLifecycle()
    val updateState by settingsViewModel.updateState.collectAsStateWithLifecycle()
    val suppressUpdatePrompt by settingsViewModel.suppressUpdatePrompt.collectAsStateWithLifecycle()
    val privacyMode by settingsViewModel.privacyMode.collectAsStateWithLifecycle()
    val nerdMode by settingsViewModel.nerdMode.collectAsStateWithLifecycle()
    val serversSnapshot by settingsViewModel.serversSnapshot.collectAsStateWithLifecycle()

    RequestStartupPermissions(settingsViewModel)

    val showSplitSheet = rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Нижний лист серверов (всегда виден).
    val sheetScaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            enabledValues = setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded)
        )
    )
    // Запрос VPN-разрешения для WireGuard.
    val wireGuardPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (VpnService.prepare(context) == null) {
            proxyViewModel.startProxy()
        }
    }

    fun startProxyWithTunnel() {
        if (clientConfig.wireGuardActive) {
            val vpnIntent: Intent? = VpnService.prepare(context)
            if (vpnIntent != null) {
                wireGuardPermissionLauncher.launch(vpnIntent)
                return
            }
        }
        proxyViewModel.startProxy()
    }

    val sheetColor = MaterialTheme.colorScheme.surfaceContainerLow

    // Без серверов: Scaffold с приглашением добавить. Не загружен: пустое тело.
    when {
        !serversSnapshot.loaded ->
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding))
            }

        serversSnapshot.list.isEmpty() ->
            Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
                HomeEmptyState(
                    onAddServer = {
                        HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                        onAddServer()
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

        else -> BottomSheetScaffold(
            scaffoldState = sheetScaffoldState,
            sheetPeekHeight = 84.dp,
            sheetContainerColor = sheetColor,
            sheetContent = {
                ServersSheetContent(
                    snapshot = serversSnapshot,
                    privacyMode = privacyMode,
                    nerdMode = nerdMode,
                    callLink = clientConfig.vkLink,
                    // Правка ссылки только пока прокси стоит (новая комната = реконнект).
                    callLinkLocked = proxyState !is ProxyState.Idle && proxyState !is ProxyState.Error,
                    onApplyServer = { id ->
                        settingsViewModel.applyServer(id)
                        scope.launch { sheetScaffoldState.bottomSheetState.partialExpand() }
                    },
                    onOpenServerSettings = { id ->
                        // Сворачиваем лист перед уходом в настройки.
                        scope.launch { sheetScaffoldState.bottomSheetState.partialExpand() }
                        onOpenServerSettings(id)
                    },
                    onSaveCallLink = { settingsViewModel.setActiveVkLink(it) }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = SettingsContentMaxWidth)
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Bar (PWDTT style)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.plug_connected_24px),
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "FreeTurn",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    letterSpacing = (-0.5).sp
                                ),
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        Surface(
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                onAddServer()
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier
                                .size(38.dp)
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline,
                                    androidx.compose.foundation.shape.RoundedCornerShape(11.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.add_24px),
                                    contentDescription = "Добавить сервер",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(Modifier.weight(1.65f))
                    Spacer(Modifier.height(40.dp))

                    ConnectionHero(
                        state = proxyState,
                        uptimeText = uptimeText,
                        tunnelActive = tunnelActive,
                        onToggle = {
                            when (proxyState) {
                                is ProxyState.Idle, is ProxyState.Error -> {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_ON)
                                    startProxyWithTunnel()
                                }
                                // CaptchaRequired: прокси под капчей работает - тоггл его останавливает.
                                is ProxyState.Running, is ProxyState.Connecting,
                                is ProxyState.Starting, is ProxyState.CaptchaRequired -> {
                                    HapticUtil.perform(context, HapticUtil.Pattern.TOGGLE_OFF)
                                    proxyViewModel.stopProxy()
                                }
                            }
                        }
                    )

                    Spacer(Modifier.weight(1f))

                    // Индикатор split-tunneling (только для WG) внизу ближе к шторке
                    if (clientConfig.wireGuardActive) {
                        SplitTunnelChip(
                            splitActive = clientConfig.splitTunnelMode != SplitTunnelMode.ALL,
                            onClick = {
                                HapticUtil.perform(context, HapticUtil.Pattern.CLICK)
                                showSplitSheet.value = true
                            }
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (showSplitSheet.value) {
        SplitTunnelModal(
            mode = clientConfig.splitTunnelMode,
            apps = clientConfig.splitTunnelApps,
            locked = proxyState !is ProxyState.Idle && proxyState !is ProxyState.Error,
            onModeChange = settingsViewModel::setSplitTunnelMode,
            onAppsChange = settingsViewModel::setSplitTunnelApps,
            onDismiss = { showSplitSheet.value = false },
            containerColor = sheetColor
        )
    }

    UpdateDialogs(
        updateState = updateState,
        suppressAvailablePrompt = suppressUpdatePrompt,
        onDownload = settingsViewModel::downloadUpdate,
        onInstall = settingsViewModel::installUpdate,
        onReset = settingsViewModel::resetUpdateState
    )
}
