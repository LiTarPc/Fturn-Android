# Fturn 4.3.9 candidate verification

This candidate combines the previously tested sing-box 1.14.4 migration, rootless router FOU automatic pairing, authorization-resume recovery, upstream manual CAPTCHA resource fixes and foreign share-link compatibility. Stable publication is withheld pending final candidate device/router acceptance.

## Pinned cores

- TURN: upstream d2c8d27bf632ce4e10e8f9c4458d932a74d32359 (FTcore 4.2.6), Go 1.26.11. Canonical Git blob inputs and checksums are in third_party/freeturn-core/SOURCE.json.
- RAW: upstream df69f213b07d958ae0d8f2d7d1693e0ad8f2163f (PR #1 to core 1.1.0), Go 1.26.10. Android version 1.1.0-captcha.1 changes only the embedded version to distinguish the unreleased CAPTCHA patch; SOURCE.json records the exact origin and local version-only patch.
- libbox: 1.14.4 with existing incoming/outgoing SOCKS UDP lifecycle fixes and manual/automatic FOU receiver. Local build rejects AARs missing required symbols. Signed/release workflows run FOU pairing/packet lifecycle tests under the race detector.

## Link compatibility

Compared with hackdiaz-dev/turn-proxy-android at bab60c5fb620cbea2570eeeb5a0697dc355071f4. Its freeturn v1 envelope stores an optional call link as vk; our existing envelope uses links. The importer now accepts both, giving an existing nonblank links field precedence. An absent/blank call link requires input in ordinary mode, remains visible while typing and receives focus; a supplied call link pre-fills the import state. Existing RAW and bare Base64/VLESS paths retain their parsers.

Regression fixtures cover foreign vk links, absent/blank values, legacy links and precedence, Base64 with/without scheme and WG/relay metadata. Actual Honor UI checks confirmed two editable fields and focused call input without vk, and the normal single-name field when vk is supplied, with expert mode disabled. Dummy profiles were not saved or connected.

## Completed local checks

- TURN all-module Go tests: 673 passing test/subtest results, no failures/skips.
- RAW all-module Go tests: 738 passing test/subtest results, 8 platform/optional skips, no failures.
- Three Node resource/proof fixtures passed; checks retain correct CDN paths, HTTPS, Request method/body/header and existing proof-capture behavior.
- JVM: 228 tests, zero failures/errors/skips; lintDebug and assembleDebug passed.
- Both Android native builds passed source pin validation. The APK embeds the generated metadata and native binaries.
- Four explicit on-device authorization-recovery regressions passed on Honor TFY-LX1, serial ALDE6R2B04001441. Candidate updated the isolated com.litar.freeturn.routerip package without clearing data; the main com.litar.freeturn application was not changed.

## Pending gates

Final acceptance on the actual Omni, fresh live VK manual widget rendering/completion, sustained load and a full router reboot are not claimed. On 2026-11-14 the user deferred the final Omni check ("потом как то проверю"). This remains a draft candidate; no stable tag or release is published.

## Signed candidate and physical USB acceptance on 2026-11-14

GitHub Signed APK run 43605261768 at 0017d04528b724abe0dcdc303ffd89c92c701cd2 passed every gate, including incoming SOCKS UDP lifecycle, FOU race regressions, RAW cancellation/reconnect repetitions, provider/browser/IPC race checks, delayed TURN authorization integration, 228 JVM tests, lint and release packaging/signing. The downloaded artifact's SHA-256 manifest passed. APK signature verified against the original release certificate 95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f.

Signed candidate APK SHA-256: 33b9f641407ee30f98a74cd4aeab85e949c029b6cc0eb74d37e63dbb8c079c72. All three native libraries (libfreeturn.so, libfturnraw.so, libbox.so) were copied/compared into the isolated debug package and verified byte-for-byte against this signed APK before final physical USB tests. The app's production code is unchanged; the post-build instrumentation addition only allows an explicitly selected saved profile to be used without reconstructing server parameters.

On Honor TFY-LX1, the original saved RAW profile started and completed HTTPS with normal certificate verification. Automatic FOU then passed six checks per core over actual PC-to-phone USB tethering: UDP DNS, TCP DNS and certificate-verified HTTPS from two independent virtual clients. The phone kept its working Wi-Fi upstream during this controlled USB-to-PC test. Both RAW and TURN/WireGuard passed 6/6; the SOCKS listener was confirmed closed, a wrong pairing key returned 401, negotiated addresses/MTU matched, real VPN traffic advanced and normal stop completed. An earlier LAN trial also passed 6/6 per core. This proves the new APK's receiver and both core data paths over USB; it does not substitute for the final Omni cable/recovery check.

Keep the failed exploratory trials visible: an archived RAW fixture with different server/key/MTU/uplink did not finish the encrypted handshake. A different UDP RAW profile connected through an existing outer router VPN but showed HTTPS and UDP-DNS timeouts. Temporary direct routing for only the test phone produced VK HTTPS connection refusals; an independent cellular-only trial did not start within the bounded window. These trials do not establish a new core regression or a network-wide stability fix, and successful tests on the original profile do not prove arbitrary nested UDP VPN paths. No universal stability claim is made.

Cleanup was completed: original test-app preferences were restored and matched their initial SHA-256, temporary instrumentation/probes were removed, Wi-Fi and USB functions were restored, and the router's temporary per-device rule was removed. Changing the Windows USB interface metric was denied by the OS and made no change. The ordinary installed Fturn package and existing router service/configuration were not updated. The isolated router test app remains at 4.3.9 with its original saved settings.

Local detailed evidence is in .native/compat-release-439/: signed-verification.json, exact-native-match.json, local-acceptance.json, phone-import-ui.json, original-profile RAW HTTPS log, USB FOU client reports and restoration.json. Private profile files and router pairing codes are excluded from Git and artifacts.
