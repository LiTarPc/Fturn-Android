package com.freeturn.app.domain.update

import com.freeturn.app.domain.UpdateState
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallCoordinatorTest {
    private class FakePlatform : UpdateInstallCoordinator.Platform {
        var apk = true
        var allowed = true
        var permissionOpened = 0
        var installerOpened = 0
        var failInstaller = false
        override fun hasDownloadedApk() = apk
        override fun canInstallPackages() = allowed
        override fun openInstallPermissionSettings() { permissionOpened++ }
        override fun launchInstaller() {
            if (failInstaller) throw IllegalStateException("installer unavailable")
            installerOpened++
        }
    }

    @Test fun `verified APK opens installer when permission is already granted`() {
        val host = FakePlatform()
        val states = mutableListOf<UpdateState>()
        val coordinator = UpdateInstallCoordinator(host, states::add)
        coordinator.install()
        assertEquals(1, host.installerOpened)
        assertEquals(0, host.permissionOpened)
        assertTrue(states.isEmpty())
    }

    @Test fun `missing permission opens settings and installs only after grant and return`() {
        val host = FakePlatform().apply { allowed = false }
        val states = mutableListOf<UpdateState>()
        val coordinator = UpdateInstallCoordinator(host, states::add)
        coordinator.install()
        assertSame(UpdateState.InstallPermissionRequired, states.last())
        assertEquals(1, host.permissionOpened)
        assertEquals(0, host.installerOpened)
        host.allowed = true
        coordinator.onAppResumed()
        coordinator.onAppResumed()
        assertEquals(1, host.installerOpened)
    }

    @Test fun `refusal preserves ready APK without reopening permission settings`() {
        val host = FakePlatform().apply { allowed = false }
        val states = mutableListOf<UpdateState>()
        val coordinator = UpdateInstallCoordinator(host, states::add)
        coordinator.install()
        coordinator.onAppResumed()
        coordinator.onAppResumed()
        assertSame(UpdateState.ReadyToInstall, states.last())
        assertEquals(1, host.permissionOpened)
        assertEquals(0, host.installerOpened)
    }

    @Test fun `cancelled permission flow does not launch on a later resume`() {
        val host = FakePlatform().apply { allowed = false }
        val coordinator = UpdateInstallCoordinator(host) {}
        coordinator.install()
        coordinator.cancel()
        host.allowed = true
        coordinator.onAppResumed()
        assertEquals(0, host.installerOpened)
    }

    @Test fun `missing APK is reported instead of launching an empty installer`() {
        val host = FakePlatform().apply { apk = false }
        val states = mutableListOf<UpdateState>()
        UpdateInstallCoordinator(host, states::add).install()
        assertTrue(states.last() is UpdateState.Error)
        assertEquals(0, host.installerOpened)
    }

    @Test fun `installer failure becomes a visible update error`() {
        val host = FakePlatform().apply { failInstaller = true }
        val states = mutableListOf<UpdateState>()
        UpdateInstallCoordinator(host, states::add).install()
        assertTrue((states.last() as UpdateState.Error).message.contains("installer unavailable"))
    }
}
