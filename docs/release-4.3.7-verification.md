# Fturn 4.3.7 candidate verification (2026-10-31)

Status: signed APK preparation; stable promotion is pending real application acceptance.

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

## Remaining acceptance and limits

Install the signed same-certificate 4.3.7 over the existing package with profiles retained; verify runtime core provenance, normal settings UI, real RAW, DNS/TCP/UDP/HTTPS, hotspot and controlled reconnect. Capture real application health through an idle/load window. Do not manufacture repeated live CAPTCHA requests for testing.

The user currently keeps the phone connected to the PC. Physical USB-router and 12–24 hour router acceptance are deferred. Short component/phone tests do not establish that all historical overnight transport losses are fixed, or prove the router path stable. Stable promotion must preserve this distinction.
