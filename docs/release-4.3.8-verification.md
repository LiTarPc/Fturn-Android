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

The delayed-credentials defect was reproduced against test.3 and fixed in test.4, pinned to a239427a201efb6725f2288ecf1c0986cfee6a0e. Only internal/proxy/udprelay/loop.go changes in production; two test files cover delayed credentials, two groups/12 streams, cancellation and typed timeout causes. Independent review matched production SHA-256 e165b645eb48d59b47b21e2036bcb74f1316d3bffe26846c166ccd7cc15fbd20 and found no blocking issue. CLI, mobile API, server wire protocol and TURN dependency are unchanged. New Linux race integration checks are mandatory in CI and signing workflows. Final signed APK and a >=12-minute physical-device TURN/maintenance run are pending.
