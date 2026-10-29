# Fturn 4.3.6 verification (2026-10-29)

Integrated source commit: afe29a87ce3da2b4e001f0fc9de7942f57031145.
Signed build: https://github.com/LiTarPc/Fturn-Android/actions/runs/40860085779 .
All signed CI steps passed, including both race regressions, RAW/TURN packages,
Android unit tests, lint, release assembly and certificate/provenance checks.
App test/lint/build took 7m51s.

## Pinned changes

- RAW 1.0.1-beta.android.4, Go 1.26.10.
- RAW source snapshot: 7f347d5fef2a3fbccdd2a475646b07f15e33c7ade862cf4249df721df182fd9f.
- RAW binary SHA-256: 08229c3ca0f53efee35b71177705b07a7f11ade6104d506b982dbb26f6458db9.
- TURN remains 4.2.4-test.1.
- sing-box remains 1.12.0 with the private pinned sing v0.7.0-beta.2 client patch.
- The bridge limit remains 1024. UDP association idle is two minutes, with
  activity in either direction preserving the association. Destination flow
  cleanup is separate. Stats and rate-limited admission diagnostics are enabled.

Only core-version.txt and rawsocks/proxy.go changed in the previous RAW snapshot,
plus three new rawsocks files and SOURCE.json. Both existing reconnect fixes
remain. Source bytes are canonical LF; all committed inputs must match SOURCE.
The local pinned rebuild matched the final canonical candidate binary above.

## Component checks completed before APK assembly

The core audit passed 36 cmd/internal package suites and go vet, 18 top-level
rawsocks tests on Honor, saturation/reclamation/reopen of all 1024 slots, and
one-way active upload/download across multiple idle intervals. An Android
production-timer check reclaimed the idle association after 120.003 seconds and
then reopened it without restarting the device/bridge. The final LF build was
also checked on Honor for binary checksum and --version.

The actual sing SOCKS client originally failed four FIN/reset Read/ReadPacket
cases: it waited for the UDP read deadline instead of observing TCP termination.
The client patch passed three runs on Windows and three on Honor, including
reopen/exchange cycles, client-only and simultaneous client/server Close,
repeated Close, TCP CONNECT payload and server-side control ownership. The
sing-box SOCKS outbound package compiled against the patched dependency.
CI additionally runs race detection for these regressions and RAW lifecycle.

## Baseline captured on the installed application

Honor TFY-LX1, Android 13/API 33; com.litar.freeturn 4.3.5. Original app PID 14625,
RAW PID 827. VPN had stayed connected; no restart was used for the baseline.
The separate com.litar.freeturn.raw 4.1.0 installation is preserved.

The exported pre-upgrade journal contains 139,463 lines / 19,457,591 log bytes,
zero dropped entries and the original failure history. The active profile is
45.133.251.171:56010, 40 streams / 10 per credential, TURN TCP, RAW packet,
MTU 1350, bypass enabled. This packet setting was selected by the user after
previous 4.3.5 verification and must remain unchanged through this upgrade.

All 36 baseline DNS/system-resolution/HTTPS/fixed-IP TLS checks passed, including
Discord, ChatGPT, Steam and GitHub. Forced-IP ChatGPT returned HTTP 403 while its
normal HTTPS request returned 200; neither result was a DNS or TLS failure.

## Installed APK and end-to-end idle comparison

The signed APK is installed as com.litar.freeturn 4.3.6 / 40306, label Fturn,
release/non-debuggable. APK SHA-256: 8f4ffa6e1a714119674a21575f6fcf9b1832ff892af258bab34ec4126af110d7.
Certificate SHA-256: 95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f.
Actual libbox SHA-256: d0794ecccab8b2bceff83a4d3066df66c72ba94b35caaf64be0677e3f52e6ee8.
The library's Go build information includes the private sing replacement and its
compiled function table contains the outgoing Client.DialContext control monitor
(the 4.3.5 library does not). Both embedded core metadata and binary hashes match
the pinned source builds, including the final RAW hash listed above.

STOP_PROXY ended the old core before adb install -r. START_PROXY resumed the
saved profile. No app data was cleared; the separate 4.1.0 raw-debug APK retains
its original SHA-256. The About screen displays v4.3.6, and the actual exported
runtime banner identifies FturnRaw core 1.0.1-beta.android.4. Profile fields remain
40/10, TURN TCP, RAW packet, MTU 1350 and bypass enabled.

The same production SOCKS hotspot path was tested before and after the upgrade.
The TCP control client waited on the computer through a temporary ADB forward;
a short Android helper sent one valid SOCKS UDP packet to 192.0.2.1:44444.
This reserved test destination uses a non-special UDP port so DNS interception
and protocol-specific QUIC timers do not substitute for RAW association cleanup.
No more UDP traffic was sent during the wait.

- 4.3.5: control remained open after 151.263 seconds; the host wait timed out.
- 4.3.6: control received EOF after 121.550 seconds, without VPN/process restart.
- After EOF, a new authenticated hotspot session passed HTTPS and UDP DNS.

The exported new runtime stats confirm udp_idle=2, last occupancy 6/1024,
free=1018, peak=18 and rejected=0. This verifies the cleanup mechanism in the
installed application, beyond standalone component tests. It does not establish
historical saturation of all 1024 slots during the original router incident.

## Live network, journal and final state

After installation, 36 initial checks and five repeated sets of 36 checks all
passed: 216 DNS/system-resolution/HTTPS/fixed-IP TLS checks, plus hotspot
credential rejection, HTTPS egress and UDP DNS before and after idle cleanup.
All five server egress checks passed. App PID 18011 and RAW PID 18631 remained
stable; the final Home screen showed an active tunnel at 06:38.

The new export contains 142,192 lines / 19,814,501 log bytes, zero writer drops,
and byte-for-byte retention of the entire pre-upgrade exported history. It is
saved in phone Downloads as fturn-diagnostics-1793272115853.zip. The existing
50 MiB collector remains active. Runtime stats are included in this journal.

The post-upgrade log window contains zero DNS exchange failures and zero local
UDP association EOF/reset setup failures. It does contain nine 15-second outbound
connect timeouts reported as SOCKS reply 4, plus two intentional invalid-password
errors from the authentication test. These are separated from the mass bridge
failure; the candidate does not demonstrate that every destination timeout is
resolved.

Temporary probe JARs, ADB forwards and the encrypted temporary credential copy
were removed. The original USB/screen keep-awake setting (0) was restored. The
phone is left on the main Fturn Home screen with the preserved profile connected.
No public stable release was promoted by this verification run.

End-to-end computer -> router -> USB phone coverage and sustained multi-device
traffic remain a separate live check: the phone was attached to the computer
for this verification. The bounded local comparison and repeated network checks
support this fix; they do not prove long-term stability under router load.
