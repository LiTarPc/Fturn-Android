# Fturn 4.3.3 verification - 28 October 2026

Status: signed candidate installed and checked through the actual Windows ->
router -> USB phone -> sing-box hotspot -> RAW path. The reported intermittent
minute-long stalls were not reproduced during these bounded checks. This report
does not establish that every earlier connectivity symptom is resolved.

## Confirmed defect and scope

On installed 4.3.2, sing-box logged EOF after about 15.1 seconds when the RAW
bridge could not connect to a destination. The bridge set a 15-second client
socket deadline for the handshake, then started a separate 15-second outbound
dial. The socket deadline could expire before the failed dial wrote its SOCKS
reply. The host regression reproduced an EOF at 15 seconds before the fix.

The completed SOCKS request now reserves a bounded five-second reply window
after the outbound deadline. A dial timeout reports host unreachable (code 4),
while an immediate refusal remains connection refused (code 5). The 15-second
dial bound and authentication remain in place. This correction reports the cause
of a failed connection; it does not make an unresponsive destination reachable.

The installed 4.3.3 confirmed the code-4 response for documentation-only address
192.0.2.123, replacing the former EOF. The real destination 111.13.53.243 from
the original phone log also timed out in the baseline; 1.1.1.1 worked at the same
time. The root cause of that destination's reachability was not established.

## Exact candidate

- Application/build commit: `6a61f7f440b6f9db93a13545b4241a2ded449f74`.
- Signed CI: https://github.com/LiTarPc/Fturn-Android/actions/runs/40684167522
  (job 122802183067), completed successfully.
- CI artifact 12815210113, `fturn-signed-4.3.3`; ZIP SHA-256
  `b0d739ff6558d204eab36f7c7b70aa949068ed7ab482548a1751a5b308dac21f`.
- APK `fturn-4.3.3-arm64-v8a.apk`; SHA-256
  `3c1c5cfb436226bf279b81009a7943b5dd1f8f36f1f3ae148c0abfbd77f6feb7`.
- Package com.litar.freeturn, label Fturn, 4.3.3 / 40303, non-debuggable; same
  release certificate as 4.3.2:
  `95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f`.
- RAW 1.0.1-beta.android.3, Go 1.26.10; source snapshot
  `0e39d293ee16c09ef57db969d4d785df1ac1a511e09cedf37a5cbf16598ab86d`;
  binary SHA-256 `c05d4b86138058bedd4f663f32e39d144b9e687730d008a4631ece5c119036ee`.
  Local and CI/APK binaries match.
- TURN remains 4.2.4-test.1, binary SHA-256
  `874540e76158eedbf0c4ec2e43fd2dda5e530025c0c2bb44b2de663e3082324e`.
- All release checksum entries verified locally. Installed APK hash checked again
  after the router tests. This work creates a test APK, not a new public stable
  release; the existing 4.3.2 RC1 assets/tag were not modified.

## Completed validation

- Host RAW SOCKS and transport suites passed. The regression failed before the
  patch and passed after it. CI additionally passed RAW bypass/TURN suites,
  race-detector regressions, legacy TURN regressions, Android unit tests, lint,
  assembly, provenance and signing verification.
- All 13 top-level RAW SOCKS tests passed on Android ARM64, including timeout and
  refused replies, 1024-client admission/reclamation, TCP/UDP and bounded cleanup.
- Installed with `adb install -r` on the same comparison HONOR TFY-LX1. Profiles
  and hotspot credentials retained; the separate com.litar.freeturn.raw APK
  remains unchanged at SHA-256
  `18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463`.
- On the phone after updating: all 34 DNS/HTTPS probes passed, including Steam
  store/community. Hotspot authentication rejected a wrong password and accepted
  the unchanged password from the router; HTTPS had the selected server egress
  and SOCKS UDP DNS passed. HTTP/3-only requests passed to Cloudflare and Google.
- The actual router path was forced from Windows LAN address 192.168.1.238 on
  interface 15. Twelve samples over 248.3 seconds passed all 120 grouped checks:
  router/tunnel DNS over UDP/TCP, fixed-IP TLS, Steam store/community, GitHub,
  Cloudflare HTTPS and actual HTTP/3 to two hosts, without TCP fallback.
  Application/RAW PIDs remained unchanged during this window.
- During these checks, all 48 external TLS connections with certificate validation
  established and were held for 30 seconds. DNS/HTTPS/HTTP3 checks continued to
  pass. Sampled router file descriptors rose to 115 and fell after load release;
  sampled hev RSS peaked at 3776 KiB. This is not a four/five-device throughput
  or multi-hour reliability benchmark.
- Through the router, switched from 45.133.251.171 to 201.58.176.69: hotspot
  HTTPS verified the new server egress, UDP DNS passed, and two further samples
  passed all 20 grouped DNS/HTTPS/HTTP3 checks. The latter profile uses packet
  uplink; the original profile uses tcp-flow. Both were observed working.
- Returned to original 45.133.251.171: hotspot egress/authentication/DNS passed,
  and another sample passed all 10 grouped router-path checks. Fturn remains
  connected to the original profile.

## Router observations and cleanup

The existing reconnect installation from the referenced router chat was reviewed.
No router configuration was changed. Its limits remain 128 sessions and 384 open
files; TUN MTU 1420, TCP buffers 4096 and UDP inactivity 30 seconds. DNS targets
1.1.1.1/1.0.0.1 have explicit tunnel routes and fail-closed guards. Historical
warnings about 150 concurrent DNS queries were present, most recently at the
router's logged 09:43:58 on 28 October; no new such warning appeared during the
checks. Those warnings alone do not identify their cause.

When the phone was at the PC, the router had no USB upstream or hev process.
After physical reconnection, its automatic service restored the tunnel. Network
ADB was temporarily enabled on the phone and used through a localhost-only SSH
forward via the router. Router-side direct access avoided sending management
connections back through the LAN VPN policy. The first sustained network samples
ran while Android Settings was foreground, so their UI-log snapshots were not
available; their network checks and process identities were collected. Later
fresh Fturn UI snapshots were obtained for the timeout and profile checks.

After testing, named diagnostic phone files and the temporary DPAPI-encrypted
hotspot credential were removed. ADB returned to USB mode; router-side direct
connection confirmed TCP 5555 closed. The SSH forwarding helper exited. The
router automatically recovered its USB tunnel again and a final forced-LAN HTTPS
request returned 200. Original profile and both installed application packages
were preserved.
