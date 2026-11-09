# Fturn 4.3.4 diagnostic journal verification

## Implementation

Source commit: b69930434349981dc65e3458481559ceb1967d2b.
Signed CI: https://github.com/LiTarPc/Fturn-Android/actions/runs/40729648151 .
Result: SUCCESS, including RAW tests, RAW race regression, TURN client tests,
full application unit tests, lintDebug, signed assembleRelease and certificate
verification. The Android test/lint/build step completed in 8 minutes 15 seconds.
Native RAW pin stays 1.0.1-beta.android.3; this build is a diagnostics baseline.
The journal is capped at 50 MiB in ten 5 MiB segments under noBackupFilesDir.
Only explicit Clear journal deletes disk history. Automatic UI-list clearing on
reconnect does not call the persistent store's clear operation.

The IO queue is bounded to 256 commands and reports discarded entries on overload.
Export is an ordered queue barrier, producing diagnostic-info.txt and events.log.
The Android document picker selects the destination of the ZIP. Persistent and
exported entries are redacted independently of the screen privacy setting.

## Tests completed on 2026-10-28

Five JUnit tests passed on the host JVM and again on the connected Honor TFY-LX1
(Android 13 / API 33, serial ALDE6R2B02007652). Android execution took 5.351 seconds.
The same production Kotlin sources were compiled to DEX and run under ART:

- More than 50 MiB of UTF-8 input: total retained bytes <= 52,428,800, at most ten
  segments <= 5,242,880 bytes each, newest retained and oldest removed; no broken
  UTF-8 characters in the exported log.
- Close/reopen retention and chronological concatenation of rotated segments.
- Explicit clear followed by continued recording.
- Asynchronous export barrier, process-session retention and secret redaction.
- Initialization failure reports an error rather than leaving export suspended.

Before the update, the existing 4.3.3 connection passed 36 DNS/system-resolution/
HTTPS checks including Discord, ChatGPT, Steam and GitHub. A separate HTTPS trace
confirmed egress via the original RAW server. One forced-IP ChatGPT request
returned HTTP 403 while the ordinary HTTPS request returned 200; this is an HTTP
response, not a DNS or TLS transport failure.

Local full Gradle validation could not resolve its cached dependency graph and
failed while reporting ModuleVersionResolveException (null path); the standalone
JVM/ART tests succeeded. Full application tests, lint and release compilation are
performed by the signed Linux CI workflow.

## Device and practical limits

The device is connected and authorized through USB ADB. Its screen became PIN
locked while the user was away. Unlocking was requested; no PIN was requested or
entered. Until unlocked, the document-picker UI/export and settings checks remain
pending. The computer -> router -> USB phone path is unavailable while the phone
is physically connected to the computer. Successful short probes do not establish
that intermittent minute-long failures have been fixed.

## Installed artifact

Fturn 4.3.4 / versionCode 40304 was installed with adb install -r over the verified
4.3.3 APK, using the production certificate. The installed APK SHA-256 matches the
downloaded artifact:

`469724f95bb711e3f271c6fc6c441bbd9205198ce5d91cbaa2332ea1f9255675`

Certificate SHA-256:
`95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f`

Embedded RAW binary SHA-256:
`c05d4b86138058bedd4f663f32e39d144b9e687730d008a4631ece5c119036ee`

Embedded TURN binary SHA-256:
`874540e76158eedbf0c4ec2e43fd2dda5e530025c0c2bb44b2de663e3082324e`

The original separate com.litar.freeturn.raw 4.1.0 APK remains byte-for-byte
unchanged (SHA-256 18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463).
VPN authorization remained granted. The saved profile was restarted with the
existing START_PROXY shortcut activity. Application PID 8269 and RAW PID 9595
were running at verification; no fatal application/foreground-service exception
was found in that application's runtime log after installation.

After installation, another 36 DNS/system-resolution/HTTPS checks passed and a
separate HTTPS trace again confirmed the original RAW-server egress. No saved
profile was edited, no application data was cleared, and no release was promoted
to public stable. The installed diagnostics baseline retains the old native pin.

The on-device pure collector tests validate storage behavior under ART. They do
not substitute for checking the document picker and reading an export produced by
the installed, minified application. Those final UI/export checks remain pending
because the screen is PIN locked; an unlock request is pending with the user.

## UI/export follow-up after unlocking

The user unlocked the device and authorized unattended UI checks. The actual
installed/minified 4.3.4 app successfully saved a ZIP through DocumentsUI to
Downloads. It contained only diagnostic-info.txt and events.log; CRC/UTF-8 and
version metadata checks passed. The first export had 5,179 lines / 708,451 log
bytes, covering approximately 47 minutes, with RAW, sing-box and lifecycle records.

After stopping VPN, force-stopping/relaunching Fturn and starting the same profile,
a second UI export contained the entire first log as an exact byte prefix, plus
new entries: 6,539 lines / 931,073 bytes, two application sessions and two profile
sessions, with zero dropped entries. The profile retained 40 streams, 10 streams
per credential, tcp TURN, tcp-flow RAW, MTU 1350 and bypass enabled.

This closes the earlier PIN-related export/retention limitation. Inspection also
found verbose captcha request/proof payloads. They are filtered in the subsequent
4.3.5 recorder and re-filtered when exporting older segments, preserving useful
status/timing records. The two old test ZIPs are temporary diagnostics, not support
attachments; a newly filtered export should be used for sharing.
