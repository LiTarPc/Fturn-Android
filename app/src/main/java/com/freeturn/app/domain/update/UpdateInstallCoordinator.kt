package com.freeturn.app.domain.update

import com.freeturn.app.domain.UpdateState

/** Keeps the permission/settings round trip separate from Android's installer. */
internal class UpdateInstallCoordinator(
    private val platform: Platform,
    private val updateState: (UpdateState) -> Unit,
) {
    interface Platform {
        fun hasDownloadedApk(): Boolean
        fun canInstallPackages(): Boolean
        fun openInstallPermissionSettings()
        fun launchInstaller()
    }

    private var awaitingPermission = false

    fun install() {
        if (!platform.hasDownloadedApk()) {
            updateState(UpdateState.Error("Файл обновления не найден. Скачайте его ещё раз."))
            return
        }
        try {
            if (!platform.canInstallPackages()) {
                awaitingPermission = true
                updateState(UpdateState.InstallPermissionRequired)
                platform.openInstallPermissionSettings()
            } else {
                awaitingPermission = false
                platform.launchInstaller()
            }
        } catch (error: Exception) {
            awaitingPermission = false
            updateState(UpdateState.Error("Не удалось начать установку: ${error.message ?: error.javaClass.simpleName}"))
        }
    }

    fun onAppResumed() {
        if (!awaitingPermission) return
        awaitingPermission = false
        // A refusal keeps the already verified APK available; no settings loop.
        if (platform.canInstallPackages()) install()
        else updateState(UpdateState.ReadyToInstall)
    }

    fun cancel() { awaitingPermission = false }
}
