# DNS selection and failover alerts

The connection DNS options feed the core's bootstrap/outbound resolver; website
DNS is separate and remains inside the VPN profile. UDP/53 is a transport and can
also be the carrier's DNS, rather than evidence of a public resolver by itself.

Carrier DNS is read from the selected physical Network's LinkProperties. The
active physical default wins, including cellular while standby Wi-Fi exists.
If the default is a VPN, eligible validated underlays are preferred; VPN and lost
networks are excluded. Do not borrow a standby network's DNS when the chosen
network has an empty DNS list. The same policy supplies sing-box's underlay and
handover identity to avoid mismatched physical interfaces.

In auto mode the pinned WG/VLESS core 4.2.3 and RAW core 1.0.0 emit
`[DNS] UDP/53 unreachable; sticky-switching to DoH` only when the UDP probe fails
and DoH is selected. The shared parser turns this exact transition into an alert.
A successful UDP probe, explicit DoH mode, or unrelated failed request does not
create a transition alert. Missing carrier DNS is reported separately when the
core starts with its reserve resolver configuration. Manual DNS overrides carrier
DNS; intentional DoH does not produce a missing-carrier warning.

The foreground app shows a long text toast, plus a silent persistent notice in
the notification drawer. In the background the separate high-importance DNS
channel carries the notice. Android notification permissions/channel preferences
remain authoritative. One alert per transition kind is retained across watchdog
core retries. Starting a new logical connection or changing physical network
resets this deduplication. Stopping the VPN removes the DNS notification.

Validation: 162 JVM tests pass, including six physical-network selection
scenarios, three alert-deduplication cases and three core DNS log parsing cases.
Debug/release compilation and device checks are reported separately.

## Comparing CAPTCHA behavior across devices

The ordinary core derives its browser-profile fingerprint seed from ClientID and
loads a persisted profile generation and auth cooldown. Matching app versions,
phone models and shared server profiles therefore do not establish matching
local auth state. Swapping SIMs alone does not change that state. This is a
candidate explanation, not a diagnosis without paired connection logs.
Source: https://github.com/LiTarPc/fturn-core/blob/v4.2.3/internal/session/provider.go
and https://github.com/LiTarPc/fturn-core/blob/v4.2.3/internal/provider/vk/internal/vkauth/client.go.
