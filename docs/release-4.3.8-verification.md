# 4.3.8 candidate verification

This is a test candidate. No stable tag or release is authorized before a full real-device connection test.

- Both vendored source snapshots match their committed core handoffs: TURN 4.2.4-test.4 (a239427a), RAW 1.0.1-beta.android.6 (8eda3177).
- Local Gradle testDebugUnitTest, lintDebug, lintVitalRelease, assembleDebug, assembleDebugAndroidTest and assembleRelease passed. JVM: 211 tests, zero failures/errors.
- Android 13 physical-device instrumentation: 19 checks passed, including actual WebView proof observers for both cores, Go browser-proof fixture, stale-generation handling, terminal protocol errors, lifecycle and patched libbox JNI/config validation.
- Native device fixtures: RAW 287 top-level passes (2 expected skips); TURN selected suites 18 top-level passes. These use local fixtures, not live VK services.
- Private sing SOCKS UDP control regression: 3 repetitions, 15 top-level passes. Rebuilt libbox pins Go 1.24.5, includes private fturn-sing and the outgoing UDP control monitor. A preBuild guard and release artifact checks reject stale unpatched libraries.
- APK inspection verified embedded core binary/source metadata, the upstream license notice, and absence of debug-only fixture binaries/assets from release output.
- Main installed 4.3.6 was not changed during fixture tests. Its original APK was saved; this is an APK backup, not a backup of application data.

## Physical-device live acceptance of initial candidate c9712fe

GitHub Signed APK run 42452935236 passed all steps: both cores' tests/race tests, private sing SOCKS lifecycle race regression, Android unit tests/lint, signed APK assembly and certificate check. No GitHub release or stable tag was created.

The original-certificate APK was independently verified and installed over 4.3.6 as com.litar.freeturn 4.3.8 (40308). Android kept the same application data directory. All three existing server entries remained available.

- RAW android.6: 3 real browser PoW captures, 3 automatic tokens, 3 VK submissions accepted, all 21 distinct channels ready. No manual challenge was needed.
- TURN test.3: 2 real browser PoW captures, 2 VK submissions accepted, all 12 distinct channels ready. No manual challenge was needed.
- Both modes passed real UDP DNS, HTTPS content validation and HTTPS external-IP verification matching the selected server. UID 2000 was confirmed inside the active VPN UID ranges, so the probe did not test direct connectivity.
- Both modes repeated those checks successfully after 45 seconds in the background with the screen off. Each core kept the same process.
- Normal stop completed: both core processes, proxy/VPN services and the app VPN network disappeared. Teardown is asynchronous; the final check waits for completion.
- The original RAW profile was restored and the VPN left stopped, matching the starting state.

One TURN stream closed shortly after allocation and recovered about 21 seconds later. The other streams continued and real traffic passed. Its DTLS timer/credential-wait ordering is being investigated with a deterministic regression test; this warning must not be silently treated as a clean run. The user also supplied a historical 4.3.6/android.4 journal for separate analysis. Stable publication remains withheld while that review and any required follow-up candidate are pending.

This is a bounded real-device acceptance run, not a claim that manual challenges can never appear or that an overnight soak has completed. Detailed hashes, redacted auth evidence and network JSON are in E:/fturn-android-438-20261108/verified-candidate/. The signing workflow uploads a test artifact only.

## Follow-up TURN handshake fix

The delayed-credentials defect was reproduced against test.3 and fixed in test.4, pinned to a239427a201efb6725f2288ecf1c0986cfee6a0e. Only internal/proxy/udprelay/loop.go changes in production; two test files cover delayed credentials, two groups/12 streams, cancellation and typed timeout causes. Independent review matched production SHA-256 e165b645eb48d59b47b21e2036bcb74f1316d3bffe26846c166ccd7cc15fbd20 and found no blocking issue. CLI, mobile API, server wire protocol and TURN dependency are unchanged. New Linux race integration checks are mandatory in CI and signing workflows. Final signed APK validation is documented below; the timed soak was superseded by the requested SOCKS sharing check.

Dependency download recovery: signing run 42472368224 attempts 1/2 stopped before tests because the GitHub archive redirect returned HTTP 503. The direct official codeload URL was independently downloaded and matched the existing pinned archive SHA-256 5f0728f8c7054b4b03abd50c28cc8a0f8b02d9eb18936a33c86a5dd16855227c byte for byte (1,124,280 bytes). Both build scripts now use that direct URL; source version, checksum and build logic are unchanged.

## Final signed candidate and requested SOCKS sharing check

Signed run 42473305609 (commit 20da450d2e63d5a856267b9cf26715abd33032e9) passed every step, including the new delayed-authorization DTLS integration race gate. Local Android assembly/lint passed. All 11 selected changed native fixtures passed on the physical Honor. The final original-certificate APK SHA-256 is f152f94ac6ea9999cf35093fd4268a424fdfa48832510faf3c02c44631a50aca; embedded TURN binary afef142f178c047dc30cbddfaa0af98a7bc04068a8a3afcdabf3c9e4a254760d matches the local pinned build, RAW remains c44a887a6684a624831764b4d92040e000b6b4f90a0e7eb73e96964c1c05541a. Installed in place without clearing app data.

The requested final acceptance focused on SOCKS sharing rather than a timed soak. Do not claim a completed final 12-minute controlled soak. Direct PC-to-phone Wi-Fi LAN tests passed eight auth/TCP/remote-DNS/egress/UDP cases. A new live UDP DNS response still arrived after TCP control was closed; independent source review confirmed the missing incoming control EOF monitor in libbox/sing. No sharing code fix was made during this check. The physical Android soft AP was not enabled separately.

The final RAW sharing attempt stopped before opening SOCKS due to VK credential DNS lookups using [::1]:53 and returning server misbehaving; that sharing mode remains unverified in this run. New TURN logs also contain maintenance transport failures and a later DTLS receive EOF with its primary cause retained; test.4 fixes the proven premature budget, not every transport failure. Do not label overall stability clean.

Sharing was restored to disabled with the existing port/password unchanged, original RAW profile selected, VPN/core processes stopped and the LAN listener closed. Stable publication remains withheld for the confirmed incoming SOCKS UDP lifecycle defect and unresolved transport observations. Local detailed evidence: E:/fturn-android-438-20261108/socks-sharing/REPORT.md and verified-test4/.
