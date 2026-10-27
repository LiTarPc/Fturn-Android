# Fturn 4.3.2 release-candidate verification

Status: APK tested on the phone; verification through the user's router is pending. The user cannot move the phone back to the router at this stage. This report does not establish that every intermittent Internet or Chrome QUIC failure is resolved.

## Exact artifact

- Tested application/build sources: `b6c281534a0a6efe7fadbb00f47f5706c7332a79`.
- Successful signed CI: https://github.com/LiTarPc/Fturn-Android/actions/runs/40474091618 (job 122087914663).
- Artifact 12720221774, `fturn-signed-4.3.2`; ZIP SHA-256 `bc0b1fa6ddb964e7b80637a5ddc2beb4dac9184fe6c7a04bad0b51267351e415`.
- APK `fturn-4.3.2-arm64-v8a.apk`; SHA-256 `3fead709d42f62bc2ebf1d29fc24642195f6a17d476ab0916e262ef8a57f748e`.
- Package `com.litar.freeturn`, label Fturn, version 4.3.2 / 40302, non-debuggable, previous release signing certificate `95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f`.
- RAW 1.0.1-beta.android.2, Go 1.26.10; source snapshot `f9f4155e5df8e3baca94dca1b72c3893f2bf094d647792409c43e6eb8c5482b1`, binary `fbd28c4c3852cc66784e53603334a5c4b4a530afcb872e50302201c7d05b32f4`. Local and CI binaries match.
- TURN 4.2.4-test.1 unchanged, binary `874540e76158eedbf0c4ec2e43fd2dda5e530025c0c2bb44b2de663e3082324e`.

## Restored behavior and evidence

The user selected the complete older sing-box -> authenticated RAW bridge architecture. sing-box again owns Android TUN, DNS interception, bypass and hotspot. Ordinary DNS port 53 is resolved over TCP inside RAW. The bridge allows 1024 handlers; idle/full-duplex and abandoned half-closed TCP relays now expire without waiting indefinitely for a blocking read. The Java explicit proxy introduced in 4.3.1 is removed.

The user's second HONOR TFY-LX1 was connected for the comparison. Its installed `com.litar.freeturn.raw` is 4.1.0-raw-debug / 40100, APK SHA-256 `18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463`. It exactly matches the saved wine/hotspot APK. Its bytecode confirms the sing-box SOCKS inbound, DNS hijack and TCP remote DNS server. The earlier direct-TUN APK with the same displayed version is a separate file.

On the 4.3.1 production Java handler, two diagnostic regressions failed: client 129 was closed before the SOCKS greeting, and 128 closed clients could retain all slots while remote peers remained open. Live router samples also captured intermittent SOCKS greeting EOFs and upstream DNS timeouts. These observations justify correcting the handler path, but do not prove one sole cause for all reported symptoms.

## Completed checks

- Host native tests: rawsocks, rawvpn, bypass and turndial passed.
- GitHub CI: the same native suites, RAW race-detector regressions, TURN regressions, Android unit tests, Android Lint, release assembly, provenance and signature checks passed.
- Android/ARM64 native tests on the second HONOR: all 11 top-level bridge tests passed, including admission of 1024 authenticated clients, admission reclamation, TCP/UDP packet traversal, half-close, idle cleanup, cancellation and UDP flow expiry/reuse. The 1024-client test exercises admission; it is not 1024 simultaneous external TLS connections.
- Installed 4.3.2 over the existing main 4.3.1 with `install -r`. The separate old RAW debug package remains intact. Existing selected RAW profile, RU bypass, EXCLUDE application selection and enabled hotspot port 1080 were retained. The previous hotspot password authenticated successfully; the wrong password was rejected.
- Through the installed app hotspot: HTTPS returned 200 with the RAW-server egress; SOCKS UDP DNS passed.
- Through the installed VPN: Steam store/community returned HTTPS 200, login redirected and returned 200; system DNS, UDP/TCP DNS, fixed-IP TLS, GitHub and Yandex checks passed.
- 320 simultaneous external TLS connections with certificate verification all succeeded. The RAW bridge had 328 established connections including background traffic. An extra HTTPS request returned 200 under load and after release; established bridge counts returned to 7-8 background connections. This exceeds the former 256-handler cap; it is not a throughput benchmark.
- Actual HTTP/3-only client with certificate validation, without TCP fallback: Cloudflare returned 200 / HTTP/3.0 and Google returned 204 / HTTP/3.0.
- Stop removed the old RAW process, Android TUN, bridge/update listeners and hotspot listener. Reconnection succeeded in the same application process, and the restored hotspot and HTTP/3 probes passed again.
- Network ADB was temporarily enabled for the proposed router check and then returned to USB mode when the user could not move the phone. TCP ADB port 5555 was no longer listening.

Repeated hotspot/DNS/HTTP3 checks on the unchanged second session are recorded locally and will be summarized once complete.

## Practical limits

The full Windows -> router -> USB phone -> SOCKS -> RAW path has not yet been tested with this candidate. The router's hev tunnel independently permits 128 sessions with an open-file limit of 384 and about 56 MiB available to its OS. Its settings were not changed. A 1024-handler phone does not imply the router can sustain that many sessions.

No four/five-device throughput test, forced handover, all-protocol WG/VLESS regression on the second phone, or multi-hour reliability test has been completed for this candidate. Root HTTPS responses do not validate every Steam CDN/API request or an authenticated Steam client session. The candidate must remain distinguishable from a fully validated stable release until the missing router scenario is checked.
