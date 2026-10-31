RAW 1.0.1-beta.android.5, based on the verified Android 4.3.6 pin.
Android uses sing-box for TUN, routing, bypass, DNS and the hotspot SOCKS listener.
The authenticated loopback RAW SOCKS bridge carries IPv4 payload via gVisor.

Changes for Android 4.3.7:
- Authenticated, process-scoped verification IPC; see internal/captchaipc/PROTOCOL.md.
- Browser/manual host selection and explicit user-resume gate after verification
  failure; valid cached groups and existing allocations remain usable.
  A successful fresh fetch completes RESUMING even without another captcha.
- BOT, provider error 10 and HTTP/API rate-limit handling with bounded retries.
- Debug output omits raw Cookie, authorization, proof and request payload values.
- RAW liveness and TURN maintenance counters, without packet contents or secrets.
- tcp-flow uses the shared 256-packet budget without an early 64-packet path cap.
- UDP ASSOCIATE accepts an unspecified IPv6 source hint (::, port 0). The transport
  stays IPv4; this does not enable IPv6 destination forwarding.

The 1024-handler bridge limit, bidirectional 2-minute UDP idle reclamation and
prior reconnect fixes remain. Pair with the sing SOCKS TCP control EOF monitor.
The standalone direct-TUN/native bypass adapters remain in source.
SOURCE.json pins all source inputs and Go 1.26.10. Android/arm64, API 24+.
This is a candidate pin; background WebView and long-run acceptance are separate
Android integration checks and are not established by the native unit tests.
