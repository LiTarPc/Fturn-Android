RAW 1.0.1-beta.android.6 — advanced browser PoW integration candidate.

Baseline: verified android.5 SOURCE
 e9e2ef9300d9ffd3a840d409e29a120da64e9d171a81e66e2d3ee309a29dd740.
Upstream: hackdiaz-dev/free-turn-proxy v4.1.0,
465486ba96c4c2ff93443682ec900510b2e0ff9e; GPL-3.0, see LICENSE and
internal/provider/vk/internal/captcha/BROWSER-POW-PROVENANCE.md.

Android uses sing-box for TUN, routing, bypass, DNS and the hotspot listener.
This libfturnraw.so is an Android ARM64 PIE executable launched by ProcessBuilder,
not a JNI library. No server/wire format changes are required.

Advanced mode is explicitly opt-in with -advanced-captcha (or vk.advancedCaptcha
in JSON, or FREETURN_RAW_ADVANCED_CAPTCHA=1). -manual-captcha and
-auto-captcha=false override automatic verification. Existing clients default
to the legacy behavior. Authenticated Android hosts must additionally opt in to
FREETURN_RAW_CAPTCHA_PROTOCOL=2 and implement the purpose-aware contract in
internal/captchaipc/PROTOCOL.md before using advanced mode.

The new path captures the real page's browser PoW, validates challenge input,
difficulty and telemetry, aligns UA/client hints, performs automatic verification,
submits the result and then fetches credentials. PoW capture and success-token
receipt are never readiness signals. Manual fallback is separate and bounded;
provider BOT/limits, explicit user wait and valid cached groups are preserved.

Transport fixes from android.5 remain: RAW/TURN health counters, shared 256-packet
uplink budget, 1024 SOCKS handler limit, bidirectional two-minute UDP idle cleanup,
IPv6 unspecified UDP source hint, prior reconnect changes and maintenance timing.
Debug/errors omit private URLs, tokens, cookies and proof/telemetry payloads.

SOURCE.json pins source inputs with Go 1.26.10. Target android/arm64, API24+.
A local fixture/native build is not proof that VK accepts automatic verification.
Real Android service WebView E2E, race CI and controlled natural provider testing
are separate acceptance gates. This snapshot is not a public stable release.
