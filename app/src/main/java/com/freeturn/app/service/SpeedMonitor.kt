package com.freeturn.app.service

import android.net.TrafficStats
import android.os.Process
import com.freeturn.app.domain.TrafficSnapshot
import com.freeturn.app.domain.proxy.ProxyServiceState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Опрашивает TrafficStats по UID каждую секунду, обновляет [ProxyServiceState.traffic]
 * и раз в 3с передаёт форматированную строку скорости в [onSpeed] для нотификации.
 * Цикл живёт пока [isStopped] не вернёт true (или scope не отменят).
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
            var prevRx = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            var prevTx = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
            val initialRx = prevRx
            val initialTx = prevTx

            var notifTicks = 0
            while (!isStopped()) {
                delay(1000)
                val currentRx = TrafficStats.getUidRxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L
                val currentTx = TrafficStats.getUidTxBytes(uid).takeIf { it != TrafficStats.UNSUPPORTED.toLong() } ?: 0L

                // Делим на 2, учитывая двойной подсчет TrafficStats для VpnService
                val downSpeed = maxOf(0L, currentRx - prevRx) / 2
                val upSpeed = maxOf(0L, currentTx - prevTx) / 2
                val rxBytes = maxOf(0L, currentRx - initialRx) / 2
                val txBytes = maxOf(0L, currentTx - initialTx) / 2

                ProxyServiceState.setTraffic(
                    TrafficSnapshot(
                        rxBytes = rxBytes,
                        txBytes = txBytes,
                        downSpeed = downSpeed,
                        upSpeed = upSpeed
                    )
                )

                notifTicks++
                if (notifTicks >= 3) {
                    notifTicks = 0
                    onSpeed("↓ ${format(downSpeed)} ↑ ${format(upSpeed)}")
                }

                prevRx = currentRx
                prevTx = currentTx
            }
        }
    }

    private fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B/s"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB/s"
        else -> String.format(Locale.US, "%.1f MB/s", bytes / (1024f * 1024f))
    }
}
