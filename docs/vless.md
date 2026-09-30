# VLESS over FreeTurn

The Android client embeds sing-box 1.12.0 through its official gomobile `libbox` API.
WireGuard and VLESS use the same sing-box VPN service. The WireGuard Android dependency is retained only for wg-quick configuration parsing; its GoBackend service and native libraries are excluded from the APK. FreeTurn core is pinned to v4.1.3 by default.

Supported import: `freeturn://` followed by Base64URL or standard Base64 JSON, or
the bare Base64 JSON. `sb` contains one VLESS URI. The initial supported subset is
VLESS TCP with `encryption=none`, `security=none`, and no flow or extra transport
parameters. TLS, REALITY, WebSocket, gRPC, and direct VLESS profiles are not implemented.
Profiles containing both `wg` and `sb` are rejected rather than choosing silently.

The original URI is retained in storage and backups. At runtime its endpoint is
replaced with the profile's FreeTurn listener (`127.0.0.1:9000` by default).
VLESS profiles always enable FreeTurn TCP forwarding (`-mode tcp`). The remote
FreeTurn server must forward the byte stream to a compatible VLESS inbound using
the imported UUID. This update does not provision that server or issue VLESS users.

Traffic path: Android apps → Android VPN TUN → sing-box VLESS → local FreeTurn TCP
listener → FreeTurn transport → remote FreeTurn forwarding → VLESS inbound.
DNS uses TCP through the VLESS outbound. IPv4 and IPv6 routes enter the TUN;
per-app include/exclude uses Android VPN application rules. The client package is
excluded, and native outgoing sockets are protected from VPN routing.

## Build

Prerequisites: Go, Java 17 or newer, Android SDK and NDK 28.2.13676358.

Windows:

```powershell
./scripts/build-libbox.ps1
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug -PcoreFetch=all
```

Linux / CI:

```sh
bash scripts/build-libbox.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug -PcoreFetch=all
```

The source archive version and checksum are pinned in the build scripts.
The compress dependency is pinned to 1.18.3 and verified by Go module checksums. Native
sources, caches, and generated `app/libs/libbox.aar` are ignored by Git. CI builds
the library before Gradle. `-PcoreFetch=all` also supplies the existing FreeTurn
binary required for an installable debug APK.

sing-box is GPL-3.0-or-later: https://github.com/SagerNet/sing-box/tree/v1.12.0
Its license is retained in `third_party/sing-box-LICENSE`.
Native integration follows the public interfaces in `experimental/libbox` for
that exact version. Updating sing-box requires reviewing those interfaces and
the generated configuration, then rebuilding and testing the Android library.

## Device validation

Unit tests cover the supplied Base64 sample, share round trips, unsupported
parameters, persisted profiles, endpoint replacement, and TCP forwarding.
For a real connection, verify the remote forwarding target, UUID, FreeTurn client
allowlist, obfuscation key, and VK call URL. Import success and VPN permission alone
do not establish that the remote server works.

## Direct bypass rules

In Connection mode > VPN (WireGuard or VLESS), import binary sing-box `.srs` (versions 1–3) or
UTF-8 CIDR lists (`.zone`/`.txt`, IPv4/IPv6, one subnet per line). The IPdeny
button fetches https://www.ipdeny.com/ipblocks/data/aggregated/ru-aggregated.zone
on demand. A bundled 2026-09-30 snapshot (8652 subnets) is enabled by default
for new profiles and legacy profiles without bypass settings, so the first
connection needs no rule download. Saved disabled/empty configurations are
preserved. Downloading does not change the enable switch. Updating replaces
the same named file. There is no scheduled updater. Editing active rules requires
stopping the connection; other saved profiles can be edited independently.

Enable bypass to route matches through a protected direct outbound. All other
traffic remains on the selected tunnel (subject to WG AllowedIPs for partial profiles). DNS port 53 is intercepted before direct rules and its
upstream stays on the selected VPN. Domain matching uses DNS reverse mapping and protocol
sniffing. Per-app exclusions take precedence because excluded apps never enter
this TUN. A country IP list cannot identify Russian sites hosted abroad, and the
IPdeny RU list contains only IPv4.

Imported/downloaded files are copied into profile storage and included in
encrypted backups and cloned profiles. The default bundled list is stored as a
reference to the APK asset. Share links do not embed rule files. Native libbox checks imported
rules before saving. Limits: 2 MiB per file, 8 files, 4 MiB total per profile.

Both VPN protocols operate in IPv4-only mode: DNS AAAA queries receive no IPv6 records
(`dns.strategy=ipv4_only`) and route rules reject literal IPv6 traffic inside
the TUN. IPv6 capture remains enabled to prevent that traffic escaping the VPN.
This avoids the verified Yandex IPv6 TLS failure on the tested remote server.
Per-app exclusions still bypass this VPN policy.

Large imported lists are validated at import and tunnel start, rather than every
preferences read. Preferences decoding runs off the main thread.

## WireGuard through sing-box

The original wg-quick file remains in profile storage and backups. Conversion uses the official WG parser and preserves the private/public/preshared keys, IPv4 interface addresses, IPv4 AllowedIPs, keepalive, listen port, and numeric IPv4 DNS servers. The first peer endpoint is replaced with the local FreeTurn UDP listener; subsequent peer endpoints remain intact. MTU is fixed at 1280 for the TURN transport. IPv6-only profiles or peers are rejected before connecting. DNS defaults to 1.1.1.1 if no IPv4 DNS server is configured; the first configured server is selected. DNS never falls back to direct routing. A partial AllowedIPs profile requires a DNS server reachable through its peer.

WireGuard always uses UDP forwarding regardless of a stale TCP toggle; the TURN carrier can still be TCP or UDP. Additional peer hostnames resolve through a dedicated direct bootstrap DNS server, avoiding a circular dependency on the WG tunnel. App exclusions are controlled by the app's split tunnel settings, as before. RU files apply before WG AllowedIPs routing and are handled entirely inside sing-box; Android gets two capture routes instead of thousands of exclusions.

FreeTurn and sing-box foreground services share the same notification ID. Only ProxyService updates its status and statistics; notification updates alert once, and VPN service teardown detaches without removing the shared notification.

## Validation on 2026-09-30

Debug APK (`com.litar.freeturn.debug`, displayed name Fturn, version 3.7.3-beta-vless-debug) was installed on Android 13 arm64. FreeTurn v4.1.3 was downloaded with release checksum verification. 82 JVM tests passed, including WireGuard conversion, key redaction, forced UDP forwarding, partial AllowedIPs ordering and invalid-config preflight.

The embedded libbox accepted the converted real WG profile with all 8652 IPdeny subnets, the VLESS configuration with those subnets, and a binary SRS v3 fixture. Runtime WG tests with RU bypass enabled returned HTTP 200 for Yandex Internetometer and yastatic.net. Yandex reported the mobile network address, while api.ipify.org reported the WG server address, confirming different routing paths for these requests. UDP and TCP DNS requests to both the TUN DNS address and a test-net address were intercepted successfully. AAAA queries returned NOERROR with zero answers; a literal IPv6 TCP connection was rejected.

Both foreground services used notification ID 1, with one active notification displaying “Туннель активен”. Stop removed both services and all app notifications. After reconnecting and waiting for the VPN interface to become active, a fresh HTTPS probe again returned HTTP 200 with the WG server address. The APK contained libbox.so and libfreeturn.so without the legacy WG native libraries. These checks cover the tested destinations and ordinary port 53 DNS; they do not constitute a complete browser speed-test or all DNS-leak scenarios.

## Fturn 4.0.0 defaults

RU IPdeny rules ship in the APK and are enabled for new/imported profiles and legacy profiles lacking the setting. Explicitly disabled or removed rule lists remain unchanged. The default app exclusion list combines known Russian services with installed Internet-capable apps having an exact `ru` package segment (prefix, middle or suffix). Checkbox rendering and VpnService use the same selection function. A manual edit stores `splitTunnelUseDefaults=false`, so even an empty manual list stays empty through restart, cloning and encrypted backup. Include and All modes never apply automatic exclusions.

Release builds use FreeTurn v4.1.3 via gradle.properties. Tag v4.0.0 corresponds to versionName 4.0.0 and versionCode 40000. GitHub Actions tests, builds, verifies the project signature and bundled components, then publishes the arm64 APK and SHA256SUMS.txt as Fturn v4.0.0.
