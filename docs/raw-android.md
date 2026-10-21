# RAW VPN on Android (experimental)

## Build

From the Android repository in PowerShell:

```powershell
./scripts/build-raw-core.ps1
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PcoreFetch=none -PrawBuild=true
```

RAW core 1.0.0 is pinned in `raw-core.properties` and `third_party/raw-core`.
Gradle preBuild verifies SOURCE.json and compiles that source automatically with
Go 1.26.6. It does not depend on a sibling checkout or an unversioned binary.
The APK includes assets/raw-core.json with the source snapshot and binary hashes. The Android routing bridge lives in `internal/rawsocks`; the older direct TUN
adapter remains in `internal/rawvpn/device_android.go` for standalone use. The Go linker flag `-checklinkname=0` is
required by the existing `github.com/wlynxg/anet v0.0.5` Android dependency.

APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
The verified standalone phone build is saved as
`app/build/outputs/apk/phone-test/fturn-raw-wine-arm64-debug.apk`.
Package: `com.litar.freeturn.raw`, launcher label: **Fturn RAW**.
RAW debug builds use this separate package by default. Profiles, preferences,
VPN consent and app storage are independent of `com.litar.freeturn.debug` and
`com.litar.freeturn`. A first RAW installation starts with no profiles.
Use `-PrawBuild=false` only to build the ordinary `com.litar.freeturn.debug`
package, labelled **Fturn**. Save RAW and ordinary APKs under different filenames
when building both: Gradle's debug output filename is shared.

## Phone check

1. Export a `fturnraw://` configuration link from the Windows RAW client.
2. Import it through the Android application's normal add/import screen (paste,
   QR, or opening the link). Versions 1–4 are supported, including TCP/UDP TURN,
   RTP profile, MTU, stream count, streams per credential and `tcp-flow` uplink.
3. Tap Connect and accept Android's VPN prompt. No root is required.
4. Check logs: `FturnRaw core 1.0.0`, `RAW VPN`, followed by
   `RAW stream ... ready`. Only a successful RAW server handshake marks it connected.
5. Check a website/download; disconnect/reconnect, then change Wi-Fi/mobile data.

The RAW server must use the same key, RTP profile and MTU. Legacy WG/VLESS server
profiles cannot be used as RAW profiles. The default client address is
`10.77.0.2/24`; change it in Connection mode → RAW when another active client uses
that address. Server-side network/routing must support the chosen address.

Android always creates an IPv4 default VPN route, regardless of the Windows
link's `mode` (`full` or `tunnel`). Per-application include/exclude selection is
supported. CIDR and supported SRS lists now route matching IPv4 TCP/UDP traffic
directly. DNS remains inside RAW, including list updates through the private
download channel. IPv6 is rejected by the routing engine. The SOCKS bridge
transports TCP/UDP; ICMP/ping is not supported by this Android routing path.
VK/TURN control traffic stays outside the VPN because the application's UID
(and its child process) is excluded.

## Integration

The original core remains `libfreeturn.so`; RAW is selected explicitly as
`libfturnraw.so`. `VpnService` owns interface addresses, MTU, routes and lifecycle.
Sing-box owns the Android TUN and rule engine. Its RAW outbound connects to an
authenticated loopback SOCKS5 bridge in the RAW core. The bridge uses a gVisor
IPv4 TCP/UDP stack with the profile's RAW address and feeds packets to the same
RAW transport and encryption as before. No WireGuard tunnel is added. Backend
port/password are created for each session and passed in process environment,
never command logs. Client DNS resolves through RAW, without host DNS fallback.
Stop, process exit, network handover and VPN revocation close the routing engine,
backend and VPN. Legacy descriptor-transfer tests below cover the standalone
TUN adapter, which the updated Android application no longer uses.

Native Android adapter tests can run on a connected phone:

```powershell
# In the RAW core checkout (restore environment variables afterwards):
$env:GOOS='android'; $env:GOARCH='arm64'; $env:CGO_ENABLED='0'
go test -c -ldflags '-checklinkname=0' -o dist/rawvpn-android.test ./internal/rawvpn
adb push dist/rawvpn-android.test /data/local/tmp/fturn-raw-android.test
adb shell chmod 700 /data/local/tmp/fturn-raw-android.test
adb shell '/data/local/tmp/fturn-raw-android.test -test.run TestAndroid -test.v -test.timeout 30s'
```

These tests cover descriptor transfer, ownership after the original is closed,
MTU mismatch rejection, and unblocking reads on Close. They do not establish an
end-to-end connection to a live RAW server.

## Validation on 2026-10-17

- HONOR TFY-LX1, Android 13/API 33: the ARM64 core starts and reports its version.
- Native RAW package tests passed on the phone, including local Pion TURN over
  TCP and UDP, rtpopus2/rtpopus3, both uplink modes, replay rejection, stream
  replacement, reconnect recovery, and descriptor transfer/close.
- Host tests passed for `internal/rawvpn`, `internal/config`, `internal/session`.
- A connection to the user's deployed RAW server is a separate manual check.

Final APK checks: 108 Android JVM unit tests passed with zero failures, errors or
skips. The APK preserves the RAW executable byte-for-byte using
`jniLibs.keepDebugSymbols`. Installed and launched successfully on the phone;
the installed executable reports `FturnRaw core 1.0.0`. Opening a
synthetic v4 `fturnraw://` link displayed the RAW import preview; it was dismissed
without saving a profile. Temporary phone diagnostics were removed.

Standalone RAW APK SHA-256: `d7f2a631538b1c3965749ca7a188d6aa56c4dbda57b8cfd48ce29612d98b3368`.

Package isolation was verified on the phone: `com.litar.freeturn.raw` has a
different UID and zero saved profiles; the ordinary `com.litar.freeturn.debug`
installation retains its three profiles and is labelled Fturn. The standalone
RAW executable starts successfully under its new package UID. Launcher shortcuts
resolve their target package from the generated build resource.

## Rootless SOCKS5 hotspot (2026-10-20)

Enable Settings > Advanced > SOCKS5 Hotspot. Choose a port (1024..65535; default
1080) and a password (8..255 UTF-8 bytes); username is `fturn`. A random password
is generated initially. Save edits and reconnect the VPN. Enable Android's
hotspot separately and configure a SOCKS5 client on the other device using the
phone's hotspot gateway IP and the chosen port. Both TCP CONNECT and UDP
ASSOCIATE are supported; UDP also requires support in the client application.

The authenticated listener is enabled only with WG/VLESS/RAW VPN sessions and
uses their routing rules. It listens on all IPv4 interfaces, so the same port
can also be used over a reachable local LAN/USB network. Turning off the hotspot
option and reconnecting, or stopping the VPN, removes the listener. This is
explicit proxy configuration, not transparent VPN sharing for every tethered app.

Reference reviewed: hackdiaz-dev/turn-proxy-android at commit
35288db2da7c77aad510a0fbc524d8cd2bc8c244. That implementation retains the app in
its VPN and protects native control sockets. This app instead uses sing-box's
SOCKS inbound, which routes through the selected outbound while the app/core UID
stays excluded. The RAW wire protocol and server remain unchanged.

## Routing bridge validation (2026-10-20)

- 131 Android JVM tests pass, including RAW backend configuration, shared bypass
  routing, hotspot authentication/port validation and backup migration.
- RAW bridge host tests pass for TCP packet transfer/half-close, UDP ASSOCIATE,
  rejection without authentication and Close unblocking packet reads/listeners.
- Existing rawvpn and bypass package tests pass; raw-client compiles.
- Android ARM64 native core and APK build successfully. The APK preserves the
  new RAW executable byte-for-byte.
- All four native bridge tests passed on HONOR TFY-LX1 / Android 13.
- The APK was installed as com.litar.freeturn.raw. Live SOCKS tests passed with
  VLESS and with the user's RAW Finland profile: incorrect password rejected,
  HTTP TCP response received, UDP DNS response received.
- RU IPv4 downloaded successfully while RAW was active. RAW reconnected with
  IP bypass enabled, and TCP/UDP SOCKS tests passed again.
- Final hotspot routing resolves client hostnames through VPN DNS before CIDR
  matching, so domain-form SOCKS requests follow IP bypass rules too. The final
  APK was installed and its TCP/UDP authentication probe passed again on RAW.
- Actual Wi-Fi hotspot access from a second device remains a manual check;
  phone-side SOCKS listener and VPN data path were tested locally on the phone.

Historical 4.1.0 debug APK SHA-256: `18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463`.

## Release 4.2.0

The normal signed release uses versionName 4.2.0 / versionCode 40200 and bundles
RAW core 1.0.0 alongside the legacy TURN core. The GitHub release build checks
RAW metadata and binary hashes and publishes corresponding RAW source and notices.
The release certificate must match the existing Fturn certificate.

## App updater through the active VPN

Update API checks and streamed APK downloads use the authenticated loopback
CONNECT inbound owned by the active WG/VLESS/RAW service. That inbound routes
before IP bypass and partial WireGuard rules, so GitHub API and release asset
redirects stay in the tunnel. Without an active VPN, HTTPS downloads use the
ordinary connection. If the VPN is active but its download channel is missing,
the updater reports an error instead of silently retrying directly.

HTTPS certificate/hostname checks remain enabled on every redirect. API responses
are bounded to 4 MiB; APKs stream to a temporary file with a 256 MiB limit. The
completed file is promoted only after matching the installed package and signing
certificate. Cancellation, truncated responses and errors discard the partial file.

Live HONOR TFY-LX1 / Android 13 check on 2026-10-21: using the production updater
HTTP client and the actual active RAW download inbound, GitHub latest-release
metadata and all APK redirects succeeded. The full 27,739,614-byte v4.1.0 APK
streamed in 72.8 seconds; SHA-256 matched the GitHub asset digest exactly:
`768a39469ac027bfc6b855db6ab7953888f334b03ec3d4ef8f62e30482be078c`.
The diagnostic receiver was temporary, shell-protected and removed afterward;
it is absent from the release source and APK. All 138 Android JVM tests and
local debug/release builds passed.
