# Fturn 4.3.5 installed verification (2026-10-28)

Source commit: 56f4d6a088cb38f104cc4cb895fa3f5cf19c2ba0.
Signed CI: https://github.com/LiTarPc/Fturn-Android/actions/runs/40741014781 .
All steps completed successfully: RAW packages (rawvpn 120.668 seconds), RAW SOCKS
race regression, TURN packages, full app unit tests, lintDebug, signed release
assembly and certificate/provenance checks. App build/test/lint took 7m38s.

## Installed binary

- Application: com.litar.freeturn, Fturn 4.3.5, versionCode 40305, release/non-debuggable.
- Phone: Honor TFY-LX1 / Android 13 API 33, serial ALDE6R2B02007652.
- APK SHA-256: 8c7da9e24ee07fa9bc89ecf0b03ae581a532926bd79f4bbc67cb8703e8aab052.
- Certificate: 95a28637777d471e901c59cd4c1fb5ebbe6433d7128a79855106d626e3e2a85f.
- RAW: 1.0.1-beta.android.3-reconnect.1.
- RAW binary SHA-256: 23c988dd3b40e29c823d63ad85a43591ba5ba699bcd5c8050999d8c5f61d1194.
- RAW source snapshot: c196b6a2e2ab40e2280714254a06829acb43629f46231cc4d0daf303b1486360.
- TURN remains 4.2.4-test.1, binary SHA-256 874540e76158eedbf0c4ec2e43fd2dda5e530025c0c2bb44b2de663e3082324e.

The candidate was imported from the core audit's isolated Android snapshot.
All 367 listed source checksums and absence of unlisted files were verified.
Relative to the previous source, only client.go, reconnect_stress_test.go and
core-version.txt changed, plus SOURCE.json. A local pinned rebuild and the signed
APK both contain the exact candidate RAW binary hash above.

The old connection was stopped with the application's STOP_PROXY shortcut before
adb install -r. START_PROXY resumed the saved profile after the update. The actual
runtime core version was confirmed in a ZIP exported by the installed app, as
well as by embedded metadata and binary hash. The saved profile was unchanged:
40 streams / 10 per credential, TURN TCP, RAW tcp-flow, MTU 1350, bypass enabled.
No application data was cleared. The separate 4.1.0 raw-debug APK remains unchanged
(SHA-256 18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463).

## Journal and ordinary client UI

Six journal regressions passed on the host JVM (3.108s) and the same production
storage/redaction sources passed under Android ART (6.046s). Coverage includes
production 50 MiB rotation/UTF-8, reopen retention, clear/cache cleanup, ordered
export, credentials, initialization failure and legacy captcha-body filtering.

Debug mode was explicitly turned off for the UI test: the developer Logs tab was
absent while Settings > Support still offered Save journal and Send logs to
support. Opening/cancelling DocumentsUI restored the button to its normal state.
A second attempt successfully saved the ZIP into Downloads.

That ZIP contained only events.log and diagnostic-info.txt, passed CRC and UTF-8
checks, and reported app 4.3.5. It held 8,824 lines / 1,149,045 log bytes, three
application/profile sessions and zero writer drops. All preceding history from
the pre-upgrade export was retained after applying the new legacy-body filter;
39 opaque captcha request/proof bodies were redacted. The runtime version line
identified 1.0.1-beta.android.3-reconnect.1. Status and timing records remain.

The support row opened Telegram through the phone's chooser. The actual intent
resolved domain=FturnSupport and the target profile displayed @FturnSupport.
No message or attachment was sent by the test. User flow: save ZIP, open support,
attach the saved file and send in Telegram. Ordinary Settings scrolling and layout
were inspected on the actual device; both rows and their subtitles were visible.
The original debug-mode setting was subsequently restored (on), SSH restart
remained off, and the original screen/USB keep-awake setting was restored.

## Live connectivity and limits

An initial 36 DNS/system-resolution/HTTPS checks passed after installation. A
further five samples over 104.9s passed all 180 checks, with five HTTPS traces
confirming the original server egress. Targets included Discord, ChatGPT, Steam,
GitHub and fixed-IP TLS; DNS used UDP/TCP and multiple resolvers. Forced-IP ChatGPT
returned HTTP 403 while its ordinary HTTPS request returned 200; this was an HTTP
response and not a DNS/TLS transport failure.

App PID 14625 and RAW PID 15621 remained stable throughout the five samples and
at final verification. The final UI showed an active tunnel for about ten minutes;
no fatal runtime/foreground-service exception was found for the application.
Temporary probe JARs and the two older, unfiltered test ZIPs were removed from the
phone; the new filtered ZIP remains in Downloads. VPN was left connected.

The core audit independently reproduced the two reconnect defects before fixing
them and passed the standalone Android rawvpn suite (49 pass, one optional skip).
Those fault-injection tests and the short live app run support this candidate;
they do not prove every intermittent stall is resolved. The computer -> router ->
USB phone path could not be checked while the phone was attached to the computer.
No public stable release was promoted as part of this installation.
