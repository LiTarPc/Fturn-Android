# Light wine interface

The app offers Light, Dark and System appearance under Settings → Application.
System appearance is the default. Dark uses warm plum surfaces and lighter wine
text accents, while the main connection action remains deep wine with cream.
System appearance follows Android's dark-mode setting. Appearance is saved in
DataStore and included in settings backups; older backups default to System.
Wallpaper colors do not replace the brand palette.

| Role | Hex |
|---|---|
| Primary actions and selection | #65182C |
| Highlight / hover / focus | #8C3047 |
| Pressed connection button | #3B1020 |
| Content on wine | #FFF7ED |
| Background | #FCFAF6 |
| Cards | #F4F0E9 |
| Main text | #30272B |
| Secondary text | #756B6E |
| Borders / dividers | #E5DEDA |

Source tokens live in `ui/theme/Color.kt`. Supporting surface levels stay warm
and close to the card tone. System bars, splash and home-screen widget use the
chosen palette on all supported Android versions. Successful log events use green (light #28643C, dark #90D5A3).
Errors use red and warnings use amber. Connection messages from RAW, TURN/DTLS
and TCP sessions are classified as success; disconnects and negated connection
messages are not. Errors take precedence over success words in the same line. QR backgrounds remain white
for reliable scanning.

UI typography uses the system sans-serif family. Diagnostic logs and explicit
technical values may retain monospace formatting. Cards use thin 1dp boundaries;
settings groups use 1dp dividers. Decorative flower shapes and the rotating
empty-state tile are replaced by static rounded tiles. The connection action is
wine with a cream glyph, and uses the specified hover/focus and pressed colors.
A progress indicator appears while connecting. Android reduced-motion settings
are honored.

Contrast checks: cream on primary 11.50:1; cream on highlight 7.52:1; main text
on background 13.89:1; secondary text on cards 4.53:1.

Build the isolated RAW test application:

```powershell
./gradlew.bat :app:assembleDebug -PcoreFetch=none -PrawBuild=true
```

The application ID remains `com.litar.freeturn.raw`, distinct from the ordinary
`com.litar.freeturn.debug` installation. This interface update does not change
profile formats or connection settings.

## Site bypass in the client interface

Settings → Site bypass and Server → Site bypass are available without enabling
diagnostics mode. Both open the profile rule editor for WireGuard/VLESS/RAW.
Rule toggles and deletion stay locked for the active VPN while it is running.
Downloads and file imports remain available and apply on the next connection;
other profiles can also be prepared offline. RAW uses the same CIDR/SRS rule engine through its private SOCKS bridge. Proxy-only
mode explains that the consuming app chooses its routes. Empty profiles are
handled without altering saved server settings.

## Updating lists with the VPN running

WG/VLESS/RAW creates an authenticated HTTP CONNECT inbound on loopback for rule
updates. Its inbound route is placed before bypass and partial WireGuard routes
and always selects the VPN outbound. Downloads therefore work when direct
Internet access is unavailable, even though the app UID is excluded from its own
VPN to prevent control-connection loops. The download channel is removed when
the service stops and does not fall back to a direct request while that VPN is
active. RAW now shares this download channel and IP bypass support.

The bounded HTTPS downloader keeps platform certificate and hostname validation,
accepts up to 2 MB and validates the complete rule set before saving it. It does
not install a global proxy authenticator. Existing live CIDR rules are held in
the running configuration; binary rules use immutable, content-hashed filenames.
Saving a new list does not modify or restart the running tunnel. The interface
confirms that new rules apply at the next connection.

## Validation (2026-10-19)

- RAW debug APK built successfully; 126 unit tests pass with zero failures.
- Installed as `com.litar.freeturn.raw`; ordinary Fturn remains a separate package.
- Light and Dark were selected through the phone UI. The dark background
  `#1B1519` was verified in the captured application screen.
- System appearance was selected and verified in DataStore after restarting.
- Site bypass is visible in the regular client settings menu.
- RU IPv4 downloaded successfully from IPdeny with the phone connected via VLESS.
  The interface confirmed the list was saved, and the VPN remained active.
- Download tests cover authenticated CONNECT, route precedence, HTTPS-only access,
  fixed/chunked framing, truncation, size limits and endpoint lifecycle.
- Screenshots are stored locally in `.native/wine-interface/`.

Artifact: `app/build/outputs/apk/phone-test/fturn-raw-wine-arm64-debug.apk`.

SHA-256: `18da4fc3d770bcdd0043c8edf2c17da05a60da1f3e9f9251ed6e604baa705463`.

Log status update (2026-10-20): 126 unit tests pass, including RAW/TURN/TCP
success, error precedence, warnings and disconnected/negated states. Status text
contrast on log cards is at least 5.87:1 in both light and dark themes.

RAW routing/hotspot update (2026-10-20): 131 JVM tests pass. See
`raw-android.md` for architecture, setup, validation and the TCP/UDP limitation.

Final phone validation: native bridge TCP/UDP/authentication/Close tests and
SOCKS5 TCP/UDP probes passed. RAW Finland reconnected with bypass enabled.
RU IPv4 updated while RAW was active. The final APK is installed.
