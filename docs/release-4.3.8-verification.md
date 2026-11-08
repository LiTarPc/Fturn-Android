# 4.3.8 candidate verification

This is a test candidate. No stable tag or release is authorized before a full real-device connection test.

- Both vendored source snapshots match their committed core handoffs: TURN 4.2.4-test.3 (91ee6cda), RAW 1.0.1-beta.android.6 (8eda3177).
- Local Gradle testDebugUnitTest, lintDebug, lintVitalRelease, assembleDebug, assembleDebugAndroidTest and assembleRelease passed. JVM: 211 tests, zero failures/errors.
- Android 13 physical-device instrumentation: 19 checks passed, including actual WebView proof observers for both cores, Go browser-proof fixture, stale-generation handling, terminal protocol errors, lifecycle and patched libbox JNI/config validation.
- Native device fixtures: RAW 287 top-level passes (2 expected skips); TURN selected suites 18 top-level passes. These use local fixtures, not live VK services.
- Private sing SOCKS UDP control regression: 3 repetitions, 15 top-level passes. Rebuilt libbox pins Go 1.24.5, includes private fturn-sing and the outgoing UDP control monitor. A preBuild guard and release artifact checks reject stale unpatched libraries.
- APK inspection verified embedded core binary/source metadata, the upstream license notice, and absence of debug-only fixture binaries/assets from release output.
- Main installed 4.3.6 was not changed during fixture tests. Its original APK was saved; this is an APK backup, not a backup of application data.

Pending acceptance gates: Linux race tests in Actions, original-certificate signed APK verification, in-place upgrade preserving profiles, and actual VK authentication/connection/traffic testing for both modes. Browser fixtures alone do not prove a successful live VK login. No promise is made that a manual challenge can never appear.

Local detailed hashes and logs: E:/fturn-android-438-20261108/verified-candidate/. Signing workflow creates a test artifact only and does not publish a GitHub release.
