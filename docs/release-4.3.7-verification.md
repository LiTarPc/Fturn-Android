# Fturn 4.3.7 candidate verification (2026-10-31)

Status: signed candidate installed and short real-traffic checks passed. Passive long observation is pending; no stable promotion has been performed.

## Changes and provenance

- RAW 1.0.1-beta.android.5, Go 1.26.10; 391 pinned source inputs.
- Source snapshot: e9e2ef9300d9ffd3a840d409e29a120da64e9d171a81e66e2d3ee309a29dd740.
- Reproduced Android ELF SHA-256: 8fa4c5032f15cf341cf8d3ea2f1c447c95029b760db048d74ea2490539a1caf5.
- TURN and sing-box remain at the verified 4.3.6 pins, including the sing SOCKS UDP control monitor.
- Browser verification uses a service-owned ordinary WebView and authenticated local IPC. Challenge URLs and IPC credentials stay in memory, outside public UI state and diagnostics. A browser/manual fallback reuses the same challenge. Expiry waits for explicit user continuation, with no reconnect or repeated provider authorization loop. Provider Retry-After remains authoritative.
- Background verification is configurable in normal application settings and retained in encrypted backups. Existing backups default to enabled. User interaction is still necessary when the provider requires it.
- Diagnostics redact credentials in the visible log, new journal records, and historical export. Useful error reasons and timing are retained.
- RAW adds per-channel health and TURN maintenance counters, removes the separate 64-item per-path TCP queue restriction while retaining the shared bounded budget, and accepts an unspecified IPv6 SOCKS UDP source hint. This does not add IPv6 tunneling.

The background verification idea was reviewed against upstream commit 23b4ec5d6ac5d3878991d82222d11a062e503652 in hackdiaz-dev/turn-proxy-android. The Android host here is owned by the foreground service so Activity recreation does not destroy an in-progress page; it is an adaptation, not a direct cherry-pick.

## Completed before signed build

- 206 Android JVM tests: zero failures/errors/skips. Android Lint passed.
- Isolated debug-package instrumentation on Honor TFY-LX1, Android 13/API 33: 9/9 passed. Tests cover local-page heartbeat, Activity recreation, 45-second background operation, manual fallback, renderer loss, TTL/generation guards, STOP cleanup, explicit resume without a new challenge, screen off/wake, and diagnostic privacy. The fixture runs ordinary local HTML; it does not request a live CAPTCHA or start a VPN/core.
- Main application and original TURN process remained unchanged during fixture testing and removal of the two temporary QA packages. The user later manually stopped the main VPN; that stop is not an observed failure.
- Native tests on the connected Honor: 9 captchaipc and 51 vkauth top-level tests passed, plus version/provenance verification of the final ELF. Temporary executables were removed. Other native suites and host checks are recorded in the core handoff report.
- Actual historical 50 MB diagnostic corpus exported through the production store: 366826 records retained, 417 header and 620 challenge-payload checks passed, export completed in 5757 ms. No private corpus is committed.
- CI requires race tests for captchaipc, vkauth, rawsocks, rawvpn and the retained sing SOCKS UDP regression before signing.

## Signed candidate and installed application

Signed CI: https://github.com/LiTarPc/Fturn-Android/actions/runs/41227292519 .
Source commit: f8700b087c51e6339974fa42b2ecb994e7f5d967. All CI steps passed, including the RAW races, retained sing SOCKS race regression, TURN packages, Android tests/lint, signing and provenance verification.

Artifact ZIP SHA-256: 0a862f14890d0d407ad60f79105321b5911df42955da839220dbf62a24d67bc2.
APK SHA-256: 277bb2ef09c17ca8347c8635c4b8edf58cd0763f167c9514ec024009c6c8a492.
Release certificate SHA-256: 95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f.
The final APK contains the exact RAW ELF/source hashes above, the expected TURN pin and the sing SOCKS outgoing control monitor. It is com.litar.freeturn 4.3.7/40307, label Fturn, non-debuggable, with no debug fixture classes.

The installed 4.3.6 checksum was checked before adb install -r. An encrypted backup was created through the normal UI and AEAD-verified before upgrading. The post-upgrade encrypted export confirms both profiles, device identity and every existing global field are identical. The only added global field is browserCaptcha=true. The normal browser preference toggled off/on successfully and was restored on. No data was cleared.

## Real verification and transport observations

- The saved RAW profile remains TURN UDP + packet, 24 streams / 6 per credential, MTU 1350, bypass enabled. The phone remains USB-connected to the PC and uses its cellular bearer; no router settings were changed.
- Four natural provider challenges occurred. Each browser pending event became manual pending after 45 seconds, with zero host-unavailable, renderer, load, metadata or IPC-report errors. All four completed after human confirmation. There were zero browser-mode resolved events. This verifies the background host and fallback, not automatic CAPTCHA solving. An automated CAPTCHA bypass is not part of this candidate.
- After those confirmations, the latest health report contains all 24 streams active with cause=none; refresh_fail, bind_fail, decode_reject, foreign and frame_reject are zero. Wire/valid/pong ages were about 9.9 seconds. These are one short runtime sample, not an overnight stability verdict.
- Three production authenticated-hotspot rounds passed TCP DNS, UDP DNS, unspecified IPv6 source-hint compatibility, TLS/HTTP to Steam, Discord and GitHub, and the expected remote egress. ChatGPT returned HTTP 403: DNS/TCP/TLS succeeded, page access was not established.
- Actual HTTP/3 through authenticated hotspot UDP passed Cloudflare 200 and Google 204. The first helper attempt used the Go resolver's missing local ::1:53 and was invalid as an application test. The corrected helper resolves names through TCP DNS via the same SOCKS tunnel, then performs QUIC over the production UDP association; both endpoints passed and repeated successfully after the idle test.
- The exported runtime identifies the correct 4.3.7/android.5 versions, retains historical records and reports zero logger drops. A scan against five known profile/call/hotspot secrets and private challenge URLs found zero matches. The normal UI also shows the existing @FturnSupport entry.

The production-timer idle test ran while the Activity was in the launcher background. The unused UDP association closed its TCP control after 121012 ms; a fresh association immediately passed DNS. DNS/HTTPS/egress and both HTTP/3 requests passed again afterward. App/core PIDs remained unchanged. About displays v4.3.7, and the original device keep-awake setting was restored to 0 after testing.

A final pre-observation export still has all 24 channels active and zero closes. Summed latest per-stream maintenance counters: refresh 82 successful / 85 attempts, bind 190 successful / 211 attempts, failure counters zero. Attempts may be in flight or last per-stream snapshots taken at different moments; the differences are not counted as failures. Decode/foreign/frame rejects and logger drops are zero.

## Remaining acceptance and limits

Complete passive long PC observation. A real TURN TCP night comparison, the second profile on 4.3.7 and a controlled reconnect of this fully authorized session have not been performed. Preserve the current working session rather than generate additional live CAPTCHA requests merely to fill a matrix. Existing native/fixture lifecycle tests remain component evidence.

The user currently keeps the phone connected to the PC. Physical USB-router and 12–24 hour router acceptance are deferred. Short component/phone tests do not establish that all historical overnight transport losses are fixed, or prove the router path stable. Stable promotion must preserve this distinction.
