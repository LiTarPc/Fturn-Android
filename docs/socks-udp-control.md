# Outbound SOCKS UDP control lifecycle

The Android routing engine is sing-box 1.12.0 with sing v0.7.0-beta.2. Its SOCKS
client originally kept waiting on the UDP socket after the proxy closed the TCP
control connection. An idle RAW association could therefore be reclaimed by the
RAW bridge while sing-box retained an unusable outgoing packet connection.

`scripts/patch-sing-socks.py` adds a TCP control reader only after a successful
outgoing UDP ASSOCIATE handshake. Control EOF or a read error closes that
association's UDP and TCP sockets. This wakes packet readers and lets sing-box's
existing packet connection cleanup remove the old connection. A subsequent
association can then be created. This does not replay a lost UDP datagram or
silently reuse an expired association.

TCP CONNECT and the shared server-side `NewAssociatePacketConn` constructor are
unchanged. Explicit Close also closes TCP, so the monitor exits. Closing from
both paths concurrently is covered by regression tests.

The build verifies the pinned upstream module checksum and exact client source
hash, creates a private copy under `.native/sing-box-1.12.0/fturn-sing`, applies
the bounded patch, and sets a local go.mod replacement. The shared Go module
cache is unchanged. Both Windows and Linux libbox build scripts apply the patch.
The patch is compatible with the RAW association idle cleanup candidate; it does
not establish the cause of the historical router incident.

## Verification

Run after the pinned sing-box source has been downloaded:

```powershell
./scripts/test-socks-control.ps1
```

The signed APK and release workflows run `scripts/test-socks-control.sh`, which
also enables the Go race detector on Linux. Tests use the real sing SOCKS client
against a loopback peer; they require no VPN, public server or user credentials.

Checks cover normal TCP EOF and reset while UDP remains open and receives no new
packets, `Read` and `ReadPacket` unblocking, repeated create/exchange/close cycles,
concurrent explicit/server Close, unchanged TCP payload delivery, and unchanged
server-side ownership of the control stream.

On 2026-10-29 the original client failed all four EOF/reset reader cases by
waiting for its read deadline. The patched client passed three consecutive runs
on Windows and three consecutive runs on HONOR TFY-LX1 / Android 13 using a
standalone ARM64 test binary. The sing-box SOCKS outbound package also compiled
against the patched dependency. Full APK verification is a separate check.
