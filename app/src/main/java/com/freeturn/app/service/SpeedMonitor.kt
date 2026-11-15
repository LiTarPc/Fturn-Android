package com.freeturn.app.service

import com.freeturn.app.domain.proxy.ProxyServiceState
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Наблюдает за снимками трафика в [ProxyServiceState.trafficSnapshot] (источник - sing-box StatusMessage
 * в VPN-режиме или прикладные счётчики ядра в режиме локального SOCKS5), отдаёт форматированную
 * скорость в [onSpeed] и обнуляет мгновенную скорость при простое канала.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedMonitor(
    private val scope: CoroutineScope,
    private val isStopped: () -> Boolean,
    private val onSpeed: (String) -> Unit,
) {
    private companion object {
        const val STALE_SPEED_TIMEOUT_MS = 2_500L
    }

    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = scope.launch {
            var lastFormatted: String? = null
            ProxyServiceState.trafficSnapshot.collectLatest { snapshot ->
                if (isStopped()) return@collectLatest
                val formatted = "↓ ${format(snapshot.downSpeed)} ↑ ${format(snapshot.upSpeed)}"
                if (formatted != lastFormatted) {
                    lastFormatted = formatted
                    onSpeed(formatted)
                }
                if (snapshot.downSpeed > 0L || snapshot.upSpeed > 0L) {
                    delay(STALE_SPEED_TIMEOUT_MS)
                    if (!isStopped()) ProxyServiceState.clearTrafficSpeeds()
                }
            }
        }
    }

    private fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B/s"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB/s"
        else -> String.format(Locale.US, "%.1f MB/s", bytes / (1024f * 1024f))
    }
}
