RAW 1.0.1-beta sources with the Android direct-TUN integration patch (1.0.1-beta.android.1).
Android receives TUN only after an authenticated RAW stream is ready. Native control
and direct-bypass sockets are protected and bound to the physical Android network.
The native bypass reads CIDR and supported SRS destination rules. The SRS decoder
is a parser dependency; no sing-box service handles ordinary Android TUN traffic.
The legacy authenticated SOCKS entry point remains for standalone compatibility.

SOURCE.json pins all sources and Go 1.26.10. Android/arm64, API 24+.
