RAW 1.0.1-beta.android.4, based on the Android reconnect.1 source snapshot.
Android uses sing-box for TUN, routing, bypass, DNS and the hotspot SOCKS listener.
The private authenticated loopback RAW SOCKS bridge carries payload through the
RAW gVisor stack and emits IPv4 packets into the RAW transport.

The bridge admits 1024 concurrent TCP/control handlers. UDP associations expire
after two minutes without traffic in either direction, separately from destination
flow timeouts. Pair this core with the sing SOCKS client TCP control EOF monitor
in scripts/patch-sing-socks.py. Admission and lifecycle counters are logged every
30 seconds, with rate-limited admission warnings. Existing reconnect fixes remain.

The standalone direct-TUN/native bypass adapters remain in source. SOURCE.json
pins all inputs and Go 1.26.10. Android/arm64, API 24+.
