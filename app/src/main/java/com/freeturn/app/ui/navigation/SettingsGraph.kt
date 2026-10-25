package com.freeturn.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.toRoute
import com.freeturn.app.ui.screens.settings.ConnectionSettingsScreen
import com.freeturn.app.ui.screens.serverdetail.ServerDetailScreen
import com.freeturn.app.ui.screens.settings.AboutScreen
import com.freeturn.app.ui.screens.settings.AdvancedScreen
import com.freeturn.app.ui.screens.settings.BackupScreen
import com.freeturn.app.ui.screens.settings.AppScreen
import com.freeturn.app.ui.screens.settings.NerdScreen
import com.freeturn.app.ui.screens.settings.ServersListScreen
import com.freeturn.app.ui.screens.settings.SettingsScreen
import com.freeturn.app.viewmodel.proxy.ProxyViewModel
import com.freeturn.app.viewmodel.server.ServerViewModel
import com.freeturn.app.viewmodel.settings.SettingsViewModel

/** Вкладка "Настройки": Настройки -> Серверы -> [сервер] -> подключение/режим/сервер -> SSH. */
internal fun NavGraphBuilder.settingsGraph(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel,
    proxyViewModel: ProxyViewModel,
    serverViewModel: ServerViewModel
) {
    navigation<SettingsGraph>(startDestination = Settings) {
        composable<Settings> {
            SettingsScreen(
                onOpenServers = { navController.navigate(ServersList) },
                onOpenApp = { navController.navigate(AppSettings) },
                onOpenAbout = { navController.navigate(About) },
                onOpenBypass = { navController.navigate(SiteBypass()) }
            )
        }

        composable<AppSettings> {
            AppScreen(
                settingsViewModel = settingsViewModel,
                onOpenBackup = { navController.navigate(BackupSettings) },
                onOpenDiagnostics = { navController.navigate(Advanced) },
                onBack = { navController.popBackStack() }
            )
        }

        composable<BackupSettings> {
            BackupScreen(settingsViewModel = settingsViewModel, onBack = { navController.popBackStack() })
        }

        composable<About> {
            AboutScreen(settingsViewModel = settingsViewModel, onBack = { navController.popBackStack() })
        }

        composable<Advanced> {
            AdvancedScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ServersList> {
            ServersListScreen(
                settingsViewModel = settingsViewModel,
                onBack = { navController.popBackStack() },
                onOpenServer = { id -> navController.navigate(ServerDetail(id)) }
            )
        }

        composable<ServerDetail> { entry ->
            val id = entry.toRoute<ServerDetail>().serverId
            ServerDetailScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                serverViewModel = serverViewModel,
                onBack = { navController.popBackStack() },
                onOpenConnection = { navController.navigate(ClientSetup(id)) },
                onOpenBypass = { navController.navigate(SiteBypass(id)) },
                onOpenNerdInfo = { navController.navigate(NerdInfo(id)) },
                onCloned = { newId -> navController.navigate(ServerDetail(newId)) }
            )
        }

        composable<NerdInfo> { entry ->
            val id = entry.toRoute<NerdInfo>().serverId
            NerdScreen(
                serverId = id,
                settingsViewModel = settingsViewModel,
                serverViewModel = serverViewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable<SiteBypass> { entry ->
            com.freeturn.app.ui.screens.connectionmode.SiteBypassScreen(
                settings = settingsViewModel,
                proxy = proxyViewModel,
                serverId = entry.toRoute<SiteBypass>().serverId,
                onBack = { navController.popBackStack() }
            )
        }

        composable<ConnectionMode> { entry ->
            val id = entry.toRoute<ConnectionMode>().serverId
            ConnectionSettingsScreen(id, settingsViewModel, serverViewModel,
                initialTab = 1, onOpenVpn = { navController.navigate(SiteBypass(id)) },
                onBack = { navController.popBackStack() })
        }

        composable<ClientSetup> { entry ->
            val id = entry.toRoute<ClientSetup>().serverId
            ConnectionSettingsScreen(id, settingsViewModel, serverViewModel,
                onOpenVpn = { navController.navigate(SiteBypass(id)) },
                onBack = { navController.popBackStack() })
        }
    }
}
