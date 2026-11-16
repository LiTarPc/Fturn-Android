# RAW upload pauses reported after Fturn 4.4.0

Reported setup: Internet sharing through the direct USB IP tunnel. The active RAW uplink mode is not known and no Android device is visible through ADB on this workstation.

## Confirmed code defect

In RAW 1.1.0-captcha.2, TCP-flow scheduling could move an established connection between independent TURN paths after an inter-packet gap of 20 ms (subject to the 150 ms switch cooldown) or an inferred TCP retransmission (250 ms cooldown). A gap caused by ACK pacing does not establish that the previous path is drained. Even an empty local queue says nothing about packets already in flight. Marking a path bad for 15 seconds on a retransmission also treats normal TCP congestion recovery as a path failure.

Three deterministic regressions fail on the published code:

1. A later upload segment selects path 2 while 16 earlier segments remain queued on path 1.
2. A flow selects the faster new path while earlier packets may still be in flight on an 800 ms RTT path.
3. Repeated loss recovery rotates a live upload to another path.

These scenarios establish a source of cross-path packet reordering. They do not establish the exact cause of the reported real-network speedtest pauses, because the active mode and device/server logs are unavailable. Packet uplink does not use the affected TCP-flow migration logic.

## Fix candidate: RAW 1.1.0-captcha.3

Keep the chosen path for an existing flow until its worker unregisters or the idle flow binding expires. Queue, recent load and RTT selection still distribute new flows. Preserve the shared 256-packet queue budget and reconnection failover. Remove penalties and live migration inferred from TCP sequence numbers.

The focused uplink, legacy packet mode, queue-budget and reconnect regressions pass in 10 repetitions after the fix. The full RAW, SOCKS bridge and TURN transport test suites also pass locally. Linux race checks are tracked in https://github.com/LiTarPc/fturn-raw/pull/3.

## Device confirmation

Capture the existing `[RAW UPLINK]` lines during an upload pause. They contain `mode`, `queued`, `enqueue_drop`, `path_drop`, `failovers` and `queue_wait_max_ms`; no profile key is needed. Compare the same USB connection and same speedtest server before and after the fix. A physical USB speedtest and remote-server throughput measurement have not been performed in this investigation.
