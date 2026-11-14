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

Live RAW/TURN traffic and sharing, FOU traffic without SOCKS on the new APK, original-certificate signed candidate validation, and physical USB router recovery with the final candidate remain pending. Fresh live VK manual widget rendering/completion and sustained-load/router reboot acceptance are not claimed. Existing prepared-router acceptance is documented separately in docs/router-ip-auto.md and does not replace validation of this new APK.
