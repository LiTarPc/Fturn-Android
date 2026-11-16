#!/bin/sh
# Installed by Fturn over an authenticated SSH channel; no downloaded executable payloads.
set -eu
PATH=${FTURN_PATH:-/usr/sbin:/usr/bin:/sbin:/bin}
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=${FTURN_ROOT:-}
[ "$(id -u)" = 0 ] || { echo 'Administrator (root) required' >&2; exit 1; }
[ -r "$ROOT/etc/openwrt_release" ] || { echo 'OpenWrt required' >&2; exit 1; }
[ "${1:-}" = --key-file ] && [ -r "${2:-}" ] || { echo 'Usage: install-fturn-router.sh --key-file FILE' >&2; exit 2; }
key=$(cat "$2")
[ "${#key}" = 64 ] || exit 2
case "$key" in *[!0-9a-fA-F]*) exit 2;; esac
for f in fturn-fou-auto.sh fturn-fou-auto.init fturn-router-common.sh fturn-router; do test -s "$HERE/$f"; done
# Both package managers use the firmware's own configured repositories and ABI checks.
if command -v apk >/dev/null 2>&1; then
    apk update
    apk add ip-full coreutils-timeout kmod-fou kmod-ipip kmod-usb-net-rndis kmod-usb-net-cdc-ncm jsonfilter flock
elif command -v opkg >/dev/null 2>&1; then
    opkg update
    opkg install ip-full coreutils-timeout kmod-fou kmod-ipip kmod-usb-net-rndis kmod-usb-net-cdc-ncm jsonfilter flock
else echo 'No supported package manager (apk/opkg)' >&2; exit 1; fi
for tool in ubus jsonfilter nc flock timeout ip; do command -v "$tool" >/dev/null || { echo "Missing command: $tool" >&2; exit 1; }; done
. "$HERE/fturn-router-common.sh"
firewall_backend >/dev/null
lan_info=$(find_lan) || { echo 'Cannot identify a unique active LAN bridge' >&2; exit 1; }
# Validate FOU support before touching any persistent configuration.
modprobe fou 2>/dev/null || test -d "$ROOT/sys/module/fou"
modprobe ipip 2>/dev/null || test -d "$ROOT/sys/module/ipip"
# Refuse to commit someone else's pending UCI edits or overwrite a custom DNS daemon.
[ -x "$ROOT/etc/init.d/dnsmasq" ] && uci -q get dhcp.@dnsmasq[0] >/dev/null || { echo 'Standard dnsmasq required for automatic DNS setup' >&2; exit 1; }
[ -z "$(uci changes dhcp)" ] || { echo 'Apply or discard pending DHCP edits before setup' >&2; exit 1; }
if [ -r "$CONF/dhcp.installed.sha256" ]; then
    [ "$(sha256sum "$ROOT/etc/config/dhcp" | awk '{print $1}')" = "$(cat "$CONF/dhcp.installed.sha256")" ] || { echo 'DHCP changed after earlier setup; review saved DNS configuration first' >&2; exit 1; }
fi
mkdir -p "$ROOT/tmp"
transaction=$(mktemp -d "$ROOT/tmp/fturn-install.XXXXXX")
complete=0; mutated=0; had_conf=0; was_running=0; was_enabled=0
[ ! -d "$CONF" ] || { cp -a "$CONF" "$transaction/conf"; had_conf=1; }
cp "$ROOT/etc/config/dhcp" "$transaction/dhcp"
for entry in usr/sbin/fturn-fou-auto usr/sbin/fturn-router usr/lib/fturn-router/fturn-router-common.sh etc/init.d/fturn-fou-auto; do
    if [ -f "$ROOT/$entry" ]; then mkdir -p "$transaction/$(dirname "$entry")"; cp "$ROOT/$entry" "$transaction/$entry"; fi
done
if [ -x "$ROOT/etc/init.d/fturn-fou-auto" ]; then
    service_ctl fturn-fou-auto running >/dev/null 2>&1 && was_running=1 || true
    service_ctl fturn-fou-auto enabled >/dev/null 2>&1 && was_enabled=1 || true
fi
rollback() {
    trap - 0
    if [ "$complete" != 1 ] && [ "$mutated" = 1 ]; then
        service_ctl fturn-fou-auto stop 2>/dev/null || true
        service_ctl fturn-fou-auto disable 2>/dev/null || true
        cp "$transaction/dhcp" "$ROOT/etc/config/dhcp"
        service_ctl dnsmasq restart 2>/dev/null || true
        rm -rf "$CONF"
        [ "$had_conf" = 0 ] || cp -a "$transaction/conf" "$CONF"
        for entry in usr/sbin/fturn-fou-auto usr/sbin/fturn-router usr/lib/fturn-router/fturn-router-common.sh etc/init.d/fturn-fou-auto; do
            if [ -f "$transaction/$entry" ]; then cp "$transaction/$entry" "$ROOT/$entry"; else rm -f "$ROOT/$entry"; fi
        done
        if [ "$was_enabled" = 1 ]; then service_ctl fturn-fou-auto enable || true; fi
        if [ "$was_running" = 1 ]; then service_ctl fturn-fou-auto start || true; fi
        echo 'Setup failed; previous Fturn and DNS configuration restored' >&2
    fi
    rm -rf "$transaction"
}
trap rollback 0
trap 'exit 1' INT TERM HUP
mutated=1
# Preserve upgrade/legacy files; never overwrite a saved backup on repeated setup.
umask 077
mkdir -p "$CONF/backup" "$ROOT/usr/lib/fturn-router" "$ROOT/usr/sbin" "$ROOT/etc/init.d"
if [ -x "$ROOT/etc/init.d/fturn-fou-auto" ]; then
    service_ctl fturn-fou-auto stop
    [ -e "$CONF/backup/init" ] || cp "$ROOT/etc/init.d/fturn-fou-auto" "$CONF/backup/init"
fi
if [ -f "$ROOT/usr/sbin/fturn-fou-auto" ] && [ ! -e "$CONF/backup/controller" ]; then cp "$ROOT/usr/sbin/fturn-fou-auto" "$CONF/backup/controller"; fi
cp "$HERE/fturn-router-common.sh" "$ROOT/usr/lib/fturn-router/fturn-router-common.sh"
cp "$HERE/fturn-fou-auto.sh" "$ROOT/usr/sbin/fturn-fou-auto"
cp "$HERE/fturn-router" "$ROOT/usr/sbin/fturn-router"
cp "$HERE/fturn-fou-auto.init" "$ROOT/etc/init.d/fturn-fou-auto"
chmod 755 "$ROOT/usr/sbin/fturn-router" "$ROOT/usr/sbin/fturn-fou-auto" "$ROOT/etc/init.d/fturn-fou-auto"
printf '%s\n' "${lan_info%% *}" > "$CONF/lan_network"
printf '%s\n' "$key" > "$CONF/key"
chmod 600 "$CONF/key"
# DNS changes are backed up and restored only if the configuration was not edited later.
# Refuse custom DNS services instead of silently replacing their policy.
if [ -x "$ROOT/etc/init.d/dnsmasq" ] && uci -q get dhcp.@dnsmasq[0] >/dev/null; then
    if [ ! -e "$CONF/dhcp.before" ]; then cp "$ROOT/etc/config/dhcp" "$CONF/dhcp.before"; fi
    uci set dhcp.@dnsmasq[0].noresolv=1
    uci -q delete dhcp.@dnsmasq[0].server || true
    uci add_list dhcp.@dnsmasq[0].server=1.1.1.1
    uci add_list dhcp.@dnsmasq[0].server=1.0.0.1
    uci commit dhcp
    sha256sum "$ROOT/etc/config/dhcp" | awk '{print $1}' > "$CONF/dhcp.installed.sha256"
    service_ctl dnsmasq restart
else echo 'Standard dnsmasq is required; automatic DNS setup is unavailable' >&2; exit 1; fi
service_ctl fturn-fou-auto enable
service_ctl fturn-fou-auto start
complete=1
printf '%s\n' 'FTURNINSTALL_OK'
