package com.freeturn.app.domain.proxy

import com.freeturn.app.domain.TrafficSnapshot

/**
 * Accumulates monotonic session TX/RX totals across core/tunnel restarts and derives
 * instantaneous speeds without Android TrafficStats UID/loopback double counting.
 */
class TrafficAccumulator {
    private var sessionRx = 0L
    private var sessionTx = 0L
    private var lastSourceKey: Any? = null
    private var lastEpochRx = 0L
    private var lastEpochTx = 0L
    private var lastTickMs = 0L

    @Synchronized
    fun recordTotals(
        sourceKey: Any?,
        rxTotal: Long,
        txTotal: Long,
        nowElapsedMs: Long,
        reportedDownSpeed: Long? = null,
        reportedUpSpeed: Long? = null,
    ): TrafficSnapshot {
        val clampedRx = rxTotal.coerceAtLeast(0L)
        val clampedTx = txTotal.coerceAtLeast(0L)
        val newEpoch = sourceKey != lastSourceKey || clampedRx < lastEpochRx || clampedTx < lastEpochTx
        val deltaRx: Long
        val deltaTx: Long
        if (newEpoch) {
            deltaRx = clampedRx
            deltaTx = clampedTx
            lastSourceKey = sourceKey
            lastTickMs = 0L
        } else {
            deltaRx = clampedRx - lastEpochRx
            deltaTx = clampedTx - lastEpochTx
        }
        lastEpochRx = clampedRx
        lastEpochTx = clampedTx
        sessionRx += deltaRx
        sessionTx += deltaTx

        val downSpeed: Long
        val upSpeed: Long
        if (reportedDownSpeed != null && reportedUpSpeed != null) {
            downSpeed = reportedDownSpeed.coerceAtLeast(0L)
            upSpeed = reportedUpSpeed.coerceAtLeast(0L)
        } else if (lastTickMs > 0L && nowElapsedMs > lastTickMs) {
            val dtMs = nowElapsedMs - lastTickMs
            downSpeed = (deltaRx * 1000L) / dtMs
            upSpeed = (deltaTx * 1000L) / dtMs
        } else {
            downSpeed = 0L
            upSpeed = 0L
        }
        lastTickMs = nowElapsedMs
        return TrafficSnapshot(
            rxBytes = sessionRx,
            txBytes = sessionTx,
            downSpeed = downSpeed,
            upSpeed = upSpeed,
        )
    }

    @Synchronized
    fun clearSpeeds(): TrafficSnapshot {
        lastTickMs = 0L
        return TrafficSnapshot(
            rxBytes = sessionRx,
            txBytes = sessionTx,
            downSpeed = 0L,
            upSpeed = 0L,
        )
    }

    @Synchronized
    fun reset(): TrafficSnapshot {
        sessionRx = 0L
        sessionTx = 0L
        lastSourceKey = null
        lastEpochRx = 0L
        lastEpochTx = 0L
        lastTickMs = 0L
        return TrafficSnapshot.ZERO
    }
}
