package com.freeturn.app.service

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import com.freeturn.app.domain.TrafficSnapshot
import com.freeturn.app.domain.proxy.ProxyServiceState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Опрашивает TrafficStats по UID раз в 1с:
 * непрерывно накапливает переданный трафик за сессию в [ProxyServiceState.setTrafficSnapshot]
 * и отдаёт форматированную строку скорости в [onSpeed].
 * Цикл живёт в фоне пока [isStopped] не вернёт true (или scope не отменят).
 */
class SpeedMonitor(
    private val scope: CoroutineScope,
    private val isStopped: () -> Boolean,
    private val onSpeed: (String) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            val uid = Process.myUid()
            val initialRx = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            val initialTx = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            var lastRx = initialRx
            var lastTx = initialTx
            var lastTickTime = SystemClock.elapsedRealtime()

            while (!isStopped()) {
                delay(1000L)
                val now = SystemClock.elapsedRealtime()
                val dt = maxOf(1L, now - lastTickTime)

                val currentRx = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: lastRx
                val currentTx = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: lastTx

                // Делим на 2 (учитываем двойной подсчет TrafficStats для VpnService: tun + wifi/мобильная)
                val rawDeltaRx = maxOf(0L, currentRx - lastRx)
                val rawDeltaTx = maxOf(0L, currentTx - lastTx)

                val rxSpeed = ((rawDeltaRx / 2) * 1000L) / dt
                val txSpeed = ((rawDeltaTx / 2) * 1000L) / dt

                val totalRx = maxOf(0L, currentRx - initialRx) / 2
                val totalTx = maxOf(0L, currentTx - initialTx) / 2

                ProxyServiceState.setTrafficSnapshot(
                    TrafficSnapshot(
                        rxBytes = totalRx,
                        txBytes = totalTx,
                        downSpeed = rxSpeed,
                        upSpeed = txSpeed
                    )
                )
                onSpeed("↓ ${format(rxSpeed)} ↑ ${format(txSpeed)}")

                lastRx = currentRx
                lastTx = currentTx
                lastTickTime = now
            }
        }
    }

    private fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B/s"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB/s"
        else -> String.format(Locale.US, "%.1f MB/s", bytes / (1024f * 1024f))
    }
}

