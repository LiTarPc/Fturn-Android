package com.freeturn.app.domain.proxy

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.domain.StartupResult
import com.freeturn.app.domain.captcha.CaptchaUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalProxyAuthWaitTest {
    private class Launcher(private val waiting: Boolean) : ProxyServiceLauncher {
        var stops = 0
        override fun start() {
            ProxyServiceState.setRunning(true)
            ProxyServiceState.setCaptchaUi(if (waiting) CaptchaUiState(CaptchaUiState.Phase.WAITING_USER) else CaptchaUiState())
        }
        override fun stop() { stops++; ProxyServiceState.setRunning(false); ProxyServiceState.setCaptchaUi(CaptchaUiState()) }
    }
    private fun reset() {
        ProxyServiceState.setRunning(false); ProxyServiceState.setCaptchaUi(CaptchaUiState())
        ProxyServiceState.setCaptchaSession(null); ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
    }
    private val config = ClientConfig(serverAddress = "192.0.2.1:443", vkLink = "https://vk.com/call/join/test", tunnelTransport = TunnelTransport.NONE)

    @Test fun explicitUserWaitPreservesCorePastStartupTimeoutThenConnects() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            reset()
            val launcher = Launcher(true)
            val manager = LocalProxyManager(launcher, backgroundScope)
            val start = launch { manager.startProxy(config) }
            runCurrent(); advanceTimeBy(300_001); runCurrent(); start.join()
            assertEquals(0, launcher.stops)
            assertTrue(ProxyServiceState.isRunning.value)
            ProxyServiceState.setStartupResult(StartupResult.Success)
            ProxyServiceState.setConnectionStats(ConnectionStats(1, 30))
            runCurrent()
            assertTrue(manager.proxyState.value is ProxyState.Running)
            manager.stopProxy()
            assertEquals(1, launcher.stops)
        } finally { reset(); Dispatchers.resetMain() }
    }
    @Test fun ordinaryHungStartupStillStopsAtTimeout() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            reset()
            val launcher = Launcher(false)
            val manager = LocalProxyManager(launcher, backgroundScope)
            launch { manager.startProxy(config) }
            runCurrent(); advanceTimeBy(300_001); runCurrent()
            assertEquals(1, launcher.stops)
            assertFalse(ProxyServiceState.isRunning.value)
            assertTrue(manager.proxyState.value is ProxyState.Error)
        } finally { reset(); Dispatchers.resetMain() }
    }
}
