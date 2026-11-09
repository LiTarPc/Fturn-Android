package com.freeturn.app.domain.proxy

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.domain.ConnectionStats
import com.freeturn.app.domain.ProxyState
import com.freeturn.app.domain.StartupResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalProxyManagerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val validCfg = ClientConfig(serverAddress = "1.2.3.4", vkLink = "https://vk.com/x")

    private class FakeLauncher : ProxyServiceLauncher {
        var started = false
        var stopped = false
        var starts = 0
        var stops = 0
        override fun start() { started = true; starts++ }
        override fun stop() { stopped = true; stops++ }
    }

    private class ThrowingLauncher : ProxyServiceLauncher {
        override fun start(): Unit = throw IllegalStateException("not allowed")
        override fun stop() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        resetProxyState()
    }

    @After
    fun tearDown() {
        resetProxyState()
        Dispatchers.resetMain()
    }

    private fun resetProxyState() {
        ProxyServiceState.setRunning(false)
        ProxyServiceState.markTeardownComplete()
        ProxyServiceState.setStartupResult(null)
        ProxyServiceState.setConnectionStats(ConnectionStats.IDLE)
        ProxyServiceState.setCaptchaSession(null)
        ProxyServiceState.clearConnectedSince()
        ProxyServiceState.clearLogs()
    }

    // Регрессия P1 #2: FGS-фейл пишет Failed при isRunning == false - раньше менеджер ждал 5 минут.
    @Test
    fun fgsStartFailureReportsErrorWithoutWaiting() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)

        val job = launch { mgr.startProxy(validCfg) }
        runCurrent()
        assertTrue(launcher.started)

        // startForeground упал: сервис зафиксировал ошибку, isRunning так и не стал true.
        ProxyServiceState.setStartupResult(StartupResult.Failed("FGS start failed"))
        runCurrent()

        val state = mgr.proxyState.value
        assertTrue("ожидался Error, было $state", state is ProxyState.Error)
        assertEquals("FGS start failed", (state as ProxyState.Error).message)
        assertTrue(launcher.stopped)
        job.cancel()
    }

    @Test
    fun launcherExceptionReportsErrorImmediately() = runTest(dispatcher) {
        val mgr = LocalProxyManager(ThrowingLauncher())

        mgr.startProxy(validCfg)

        val state = mgr.proxyState.value
        assertTrue("ожидался Error, было $state", state is ProxyState.Error)
        assertEquals("not allowed", (state as ProxyState.Error).message)
    }

    @Test
    fun successfulStartupTransitionsToRunning() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)

        val job = launch { mgr.startProxy(validCfg) }
        runCurrent()

        ProxyServiceState.setConnectionStats(ConnectionStats(active = 1, total = 1))
        ProxyServiceState.setRunning(true)
        ProxyServiceState.setStartupResult(StartupResult.Success)
        runCurrent()

        val state = mgr.proxyState.value
        assertTrue("ожидался Running, было $state", state is ProxyState.Running)
        assertEquals(1, (state as ProxyState.Running).active)
        job.cancel()
    }

    @Test
    fun transportConnectionDoesNotReportReadyBeforeTunnelStartup() = runTest(dispatcher) {
        val mgr = LocalProxyManager(FakeLauncher())
        val job = launch { mgr.startProxy(validCfg) }
        ProxyServiceState.setRunning(true)
        ProxyServiceState.setConnectionStats(ConnectionStats(active = 1, total = 1))
        runCurrent()
        assertTrue(mgr.proxyState.value !is ProxyState.Running)
        ProxyServiceState.setStartupResult(StartupResult.Success)
        runCurrent()
        assertTrue(mgr.proxyState.value is ProxyState.Running)
        job.cancel()
    }
    @Test
    fun malformedWireGuardDoesNotStartForegroundService() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        mgr.startProxy(validCfg.copy(tunnelTransport = com.freeturn.app.data.config.TunnelTransport.WIREGUARD,
            wireGuardConfig = "[Interface]\nPrivateKey = invalid-secret"))
        assertFalse(launcher.started)
        assertTrue(mgr.proxyState.value is ProxyState.Error)
        assertFalse((mgr.proxyState.value as ProxyState.Error).message.contains("invalid-secret"))
    }
    @Test
    fun restartWaitsForPreviousTunnelCleanupBeforeLaunching() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        ProxyServiceState.markTeardownStarted()
        ProxyServiceState.addLog("Previous session is closing")

        val job = launch { mgr.startProxy(validCfg) }
        runCurrent()
        assertFalse(launcher.started)
        assertEquals("Previous session is closing", ProxyServiceState.logs.value.last().text)

        ProxyServiceState.markTeardownComplete()
        runCurrent()
        assertTrue(launcher.started)
        job.cancel()
    }

    @Test
    fun cleanupTimeoutDoesNotLaunchAnotherSession() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        ProxyServiceState.markTeardownStarted()

        val job = launch { mgr.startProxy(validCfg) }
        advanceTimeBy(15_001)
        runCurrent()
        assertFalse(launcher.started)
        assertTrue(mgr.proxyState.value is ProxyState.Error)
        assertTrue(job.isCompleted)
        assertFalse(ProxyServiceState.teardownComplete.value)
    }

    @Test
    fun stopWaitsForCleanupEvenAfterRunningFlagIsCleared() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        ProxyServiceState.setRunning(true)
        ProxyServiceState.markTeardownStarted()

        val job = launch { mgr.stopProxy() }
        runCurrent()
        assertTrue(launcher.stopped)
        ProxyServiceState.setRunning(false)
        runCurrent()
        assertFalse(job.isCompleted)

        ProxyServiceState.markTeardownComplete()
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun overlappingServiceCleanupDoesNotReleaseRestartEarly() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        // The user cancels a new service that was waiting for the old one to close.
        ProxyServiceState.markTeardownStarted()
        ProxyServiceState.markTeardownStarted()
        val job = launch { mgr.startProxy(validCfg) }
        runCurrent()

        ProxyServiceState.markTeardownComplete()
        runCurrent()
        assertFalse(ProxyServiceState.teardownComplete.value)
        assertFalse(launcher.started)

        ProxyServiceState.markTeardownComplete()
        runCurrent()
        assertTrue(launcher.started)
        job.cancel()
    }

    @Test
    fun cancelledPendingStartCannotStopTheNextConnectionOnItsOldTimeout() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        val first = launch { mgr.startProxy(validCfg) }
        runCurrent()
        mgr.stopProxy()
        advanceTimeBy(60_000)

        val second = launch { mgr.startProxy(validCfg) }
        ProxyServiceState.setRunning(true)
        // The new attempt is still authenticating when the old five-minute deadline expires.
        advanceTimeBy(241_000)
        runCurrent()
        assertEquals("The cancelled attempt must not stop the new service", 1, launcher.stops)
        assertTrue(first.isCompleted)
        first.cancel()
        second.cancel()
    }

    @Test
    fun cancelWhileWaitingForCleanupDoesNotLaunchWhenCleanupFinishes() = runTest(dispatcher) {
        val launcher = FakeLauncher()
        val mgr = LocalProxyManager(launcher)
        ProxyServiceState.markTeardownStarted()
        val start = launch { mgr.startProxy(validCfg) }
        runCurrent()
        val stop = launch { mgr.stopProxy() }
        runCurrent()

        ProxyServiceState.markTeardownComplete()
        runCurrent()
        assertFalse(launcher.started)
        assertTrue(start.isCompleted)
        assertTrue(stop.isCompleted)
    }

}
