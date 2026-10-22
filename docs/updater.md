# APK updates

The app uses pinned Fturn Core versions from freeturn-core.properties; RAW has
an independent pin in raw-core.properties. Both versions are shown in About.
Release verification checks the legacy core stamp and the exact bundled Go
binary hash. The release publishes legacy and RAW binary provenance separately.

The updater streams HTTPS downloads through the authenticated active VPN channel.
A completed APK is verified for package, certificate and a newer version code
before promotion to cache/update.apk. A trusted completed APK is restored on
startup, allowing installation after process recreation without another download.

Update dialogs are hosted by AppNavigation above all tabs. This includes errors
and ReadyToInstall, so downloading from Settings no longer hides the result.
On Android 8+ the installer coordinator opens the per-app install-source settings
when required. On return to the foreground it rechecks the permission and opens
the Android installer only if permission was granted. Refusal preserves the APK.
FileProvider grants read access through both the intent flag and ClipData.

Production assets target com.litar.freeturn with the production certificate.
They cannot replace com.litar.freeturn.raw or .debug. Package/certificate/version
failures are shown explicitly and never bypassed.

## Validation on 2026-10-22

- 147 JVM tests pass, including permission grant/refusal, return, cancellation,
  missing files and installer launch errors.
- Debug and release APKs build with legacy core 4.2.2 and RAW core 1.0.0.
- HONOR TFY-LX1 / Android 13: a newer APK with the same debug package/certificate
  was verified and the ready dialog appeared over the app Settings screen.
- Install opened Android's per-source permission page. The user manually granted
  permission and returned; the coordinator opened the system installer, which
  reached Google Play Protect's verification prompt. The fixture installation
  was cancelled, leaving the base test version and profiles intact.
- A production APK tested against the debug RAW package produced a visible
  package mismatch dialog over Settings.
- The shell-protected diagnostic receiver and fixture APKs are local test aids;
  the receiver is removed from the release source and final APK.

## CAPTCHA completion

Fturn Core 4.2.2 reports a completed manual challenge as
`[Captcha] Got token from browser`, before TURN authorization finishes.
The shared core log parser now converts this exact message to CaptchaResolved,
clearing the dialog and notification in both legacy and RAW sessions. The old
authorization success/failure formats remain supported. Regression tests use the
message captured on the affected phone and keep waiting/fallback messages open.
