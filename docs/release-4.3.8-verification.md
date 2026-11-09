# 4.3.8 candidate verification

This document records the 4.3.8 verification history. Stable publication was authorized by the user on 2026-11-09 after the functional checks below; mandatory CI and release gates must pass before publication.

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


## Incoming SOCKS5 UDP lifecycle fix and verified signed update

The confirmed sharing defect is fixed in the common libbox/sing incoming handler (f235d34; test ownership follow-up 5fa4c4c). A server-only control EOF monitor starts before the first UDP read, closes lower transports exactly once and cancels a child context. Cached packet ownership and onClose stay with the router. Early response/read errors release resources. TURN/RAW executable hashes remain unchanged. Old-handler regression failed for FIN/RST/cancel before first UDP; patched Windows and Android tests passed three repetitions (24 native top-level passes), with independent read-only source review. Linux race regression passed after correcting a concurrent cached-wrapper close in the test router; production helper did not change.

Signed workflow 42480705419 at 5fa4c4c0e889cd04ceabfd44959e2b9541d09ebe passed all Go/race/integration, Android tests/lint, packaging and signing gates. APK SHA-256 38b8641fa92e2b3d879d88ee4965949f44dc1f893438cda16b3fe339f570a34a; packaged libbox SHA-256 13d2d2fffe14c038a9dc3bd712230a1a3e779a497bcd5909820182b4e996a600. Original certificate 95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f verified independently; installed in place with profiles retained.

Actual PC-to-phone Wi-Fi LAN verification passed 11/11 checks in TURN and 11/11 in RAW: authentication rejection, strict-certificate HTTPS by domain/IP, VPN-server egress, UDP DNS (IPv4 and unspecified IPv6 hints), domain NTP, and three repeated cases each for control close after traffic, RST after traffic, and closure before first UDP. The old UDP-survives-TCP defect no longer reproduces. RAW reached 21 distinct ready channels. Its previous credential DNS startup error did not recur; this does not establish a DNS fix.

Restored original RAW profile, disabled sharing, retained port/password, stopped both cores/VPN, confirmed closed listener and removed temporary native test. Local evidence: E:/fturn-android-438-20261108/socks-sharing-fix/ (REPORT.md, results.json, verification.json, restoration.json, diagnostics.zip); signed APK in verified-socks-fix/. Diagnostics SHA-256 706a0b9f1e7406fc5df0cd72236ceacf87d322e1b3bca08085dccc093a548b8c. The earlier failed sharing check above describes the previous candidate and is superseded for this specific lifecycle defect.

No stable tag/release was created. Separate Android soft-AP acceptance and a final timed soak were not performed; the user superseded the soak with SOCKS testing. Earlier intermittent transport observations remain outside this fix; do not claim overall stability proven.


## Advanced CAPTCHA enabled by default and available in ordinary user settings

Commit ccc1027 moves the existing advanced CAPTCHA switch from Diagnostics to Settings → Application → Connection, without a nerd-mode condition, and labels it Продвинутая автокапча. Default preference, ViewModel initial state and backup fallback were already true and remain true. Saved on/off choices persist; changes apply to the next connection. Existing manual-only profile precedence is unchanged.

Local JVM 211/211, lintDebug, lintVitalRelease, assembleDebug and assembleRelease passed. Real-phone debug UI checks confirmed nerd mode disabled, default enabled, public access and persistence of both off/on states across force-stop/relaunch. Signed workflow 42483556791 at ccc1027d6767bac7b9d0c2b759fb6059461ce56a passed every gate. APK SHA-256 a0e1516f045f131cf948619404d30718d25e62c53da064ebb061cafb8d17fe64, original certificate verified, installed in place in the main package. Main installed public switch observed enabled. Both native VPN cores match the prior live-tested hashes. libbox SHA-256 bd71ef10f40324a3c5da49ec8a49e142b643d271e5a25c5c90de5322670d49b8; comparison of embedded Go build info shows only checkout module pseudo-version and Git revision/time changes, with pinned source/patches/dependencies/build flags unchanged. Both incoming/outgoing SOCKS markers and fresh CI race gate verified.

Local evidence: E:/fturn-android-438-20261108/captcha-user-mode/ (REPORT.md, verification.json, debug-ui-results.json), verified-captcha-user-mode/ and phone/captcha-user-*. VPN remains stopped with original RAW profile and disabled sharing. No stable release/tag created; no new timed soak or separate soft-AP test claimed.


## Stable release preparation authorized on 2026-11-09

The user authorized publishing 4.3.8 after the completed functional acceptance. The previously requested timed soak had been superseded by SOCKS sharing tests. The release notes explicitly retain the observed DNS/transport limitations and state that separate Android soft-AP and long-load acceptance were not performed; no overnight stability claim is made.

Remote main a6fde6e and the tested candidate branch were reconciled with a merge that introduced no file changes. Main's physical-network DNS work was already present in the candidate history; only the merge ancestry was missing. After signed candidate ccc1027, application/native code is unchanged. Release-preparation changes affect release notes, changelog and this audit only. The release path is PR CI, merge to main, then tag v4.3.8 and the existing Release workflow, which repeats tests, verifies signing/packaging and publishes the assets.


## Release gate caught RAW orderly-shutdown races

PR #14 CI run 42599310452 failed TestOrderlyClientCancellationReleasesSlot before merge/tag/publication. The unchanged android.6 fixture reproduced locally with 3 failures in 100 starts/stops. A delayed context.AfterFunc could overwrite the goodbye write deadline; a separate reader iteration could overwrite the cancellation read deadline with a future deadline and delay teardown until the TURN abort window.

The first isolated stop/join-only fix passed deterministic tests but still failed 1/100 real TURN/server slot trials, so it was not accepted alone. Combined stop/join plus a cancellation check after installing the read deadline passed 500 local slot trials; independent core review passed a separate 300 trials. Two forced-order regressions each failed consistently on the corresponding old behavior. The bundle is updated to RAW 1.0.1-beta.android.7 with pinned source inputs; stop/join remains idempotent, active blocked writes still receive immediate cancellation, and the wire/server protocol and best-effort delivery contract are unchanged.

A 100-repetition cancellation/reconnect race gate is added to PR CI, signing and release workflows. Full package, Android-native and signed/live acceptance are still required before publication. Historical android.6 acceptance above is not substituted for validation of the changed shutdown code.
