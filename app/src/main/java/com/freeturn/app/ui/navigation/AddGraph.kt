package com.freeturn.app.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.freeturn.app.ui.screens.addserver.AddServerScreen
import com.freeturn.app.ui.screens.share.QrScannerScreen
import com.freeturn.app.viewmodel.settings.SettingsViewModel

internal fun NavGraphBuilder.addGraph(
    navController: NavHostController,
    settingsViewModel: SettingsViewModel
) {
    navigation<AddGraph>(startDestination = AddServer) {
        composable<AddServer> { entry ->
            AddServerScreen(
                settingsViewModel = settingsViewModel,
                onManualCreate = { name ->
                    settingsViewModel.addManualServer(name) { id ->
                        navController.navigateToTab(SettingsGraph)
                        navController.navigate(ServerDetail(id)) { launchSingleTop = true }
                    }
                },
                onScanQr = { if (entry.isResumed()) navController.navigate(QrScanner) }
            )
        }
        composable<QrScanner> {
            QrScannerScreen(onBack = { navController.popBackStack() })
        }
    }
}
