package com.freeturn.app.domain.proxy

import com.freeturn.app.domain.TrafficSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficAccumulatorTest {

    @Test
    fun `records asymmetric download without loopback tx inflation`() {
        val acc = TrafficAccumulator()
        val first = acc.recordTotals(
            sourceKey = 1L,
            rxTotal = 20_000_000_000L,
            txTotal = 300_000_000L,
            nowElapsedMs = 1_000L,
            reportedDownSpeed = 5_000_000L,
            reportedUpSpeed = 75_000L,
        )
        assertEquals(20_000_000_000L, first.rxBytes)
        assertEquals(300_000_000L, first.txBytes)
        assertEquals(5_000_000L, first.downSpeed)
        assertEquals(75_000L, first.upSpeed)
    }

    @Test
    fun `preserves accumulated session totals across tunnel or core restart`() {
        val acc = TrafficAccumulator()
        acc.recordTotals(
            sourceKey = "tunnel-1",
            rxTotal = 1_000_000L,
            txTotal = 50_000L,
            nowElapsedMs = 1_000L,
            reportedDownSpeed = 500_000L,
            reportedUpSpeed = 25_000L,
        )
        val afterClear = acc.clearSpeeds()
        assertEquals(1_000_000L, afterClear.rxBytes)
        assertEquals(50_000L, afterClear.txBytes)
        assertEquals(0L, afterClear.downSpeed)
        assertEquals(0L, afterClear.upSpeed)

        // New tunnel instance starts from 0 internally, session totals must accumulate monotonically.
        val afterRestart = acc.recordTotals(
            sourceKey = "tunnel-2",
            rxTotal = 250_000L,
            txTotal = 10_000L,
            nowElapsedMs = 3_000L,
            reportedDownSpeed = 250_000L,
            reportedUpSpeed = 10_000L,
        )
        assertEquals(1_250_000L, afterRestart.rxBytes)
        assertEquals(60_000L, afterRestart.txBytes)
        assertEquals(250_000L, afterRestart.downSpeed)
        assertEquals(10_000L, afterRestart.upSpeed)
    }

    @Test
    fun `derives speeds from timestamps when core reports only cumulative totals`() {
        val acc = TrafficAccumulator()
        val t0 = acc.recordTotals(sourceKey = "proc-1", rxTotal = 100L, txTotal = 20L, nowElapsedMs = 1_000L)
        assertEquals(100L, t0.rxBytes)
        assertEquals(20L, t0.txBytes)
        assertEquals(0L, t0.downSpeed)
        assertEquals(0L, t0.upSpeed)

        val t1 = acc.recordTotals(sourceKey = "proc-1", rxTotal = 2_100L, txTotal = 520L, nowElapsedMs = 3_000L)
        assertEquals(2_100L, t1.rxBytes)
        assertEquals(520L, t1.txBytes)
        assertEquals(1_000L, t1.downSpeed)
        assertEquals(250L, t1.upSpeed)
    }

    @Test
    fun `reset clears session totals and speeds`() {
        val acc = TrafficAccumulator()
        acc.recordTotals(sourceKey = 1L, rxTotal = 500L, txTotal = 100L, nowElapsedMs = 1_000L, 50L, 10L)
        assertEquals(TrafficSnapshot.ZERO, acc.reset())
    }
}
