#!/bin/sh
# Fturn automatic USB FOU controller for OpenWrt/fw4. No changes to HEV configuration.
set -u
PATH=/usr/sbin:/usr/bin:/sbin:/bin
STATE=/var/run/fturn-fou-auto.state
KEY_FILE=${FTURN_FOU_KEY_FILE:-/etc/fturn-fou-auto.key}
IFACE=ftfouauto
TABLE=40081
IP=${FTURN_FOU_IP:-/usr/libexec/ip-full}
CONTROL_PORT=40081
TIMEOUT=${FTURN_FOU_TIMEOUT:-/usr/bin/timeout}
# A LAN source narrows acceptance during installation tests. Empty means all br-lan.
TEST_SOURCE=${FTURN_FOU_TEST_SOURCE:-}
log() { logger -t fturn-fou-auto "$*"; }
ipcmd() { "$IP" "$@"; }
valid_ip() { printf '%s\n' "$1" | awk -F. 'NF!=4 {exit 1} {for(i=1;i<=4;i++) if($i!~/^[0-9]+$/ || $i>255) exit 1}'; }
valid_port() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]; }
cleanup() {
    [ -r "$STATE" ] || return 0
    read -r old_phone old_router old_port old_remote old_mtu old_lan < "$STATE" || return 1
    ipcmd -4 rule del priority 1078 iif br-lan lookup "$TABLE" 2>/dev/null || true
    # The exact source is recorded separately so cleanup also works after restart.
    old_source=$(cat /var/run/fturn-fou-auto.source 2>/dev/null || true)
    if [ -n "$old_source" ]; then ipcmd -4 rule del priority 1078 from "$old_source/32" iif br-lan lookup "$TABLE" 2>/dev/null || true; fi
    ipcmd -4 rule del priority 1077 to "$old_phone/32" iif br-lan lookup main 2>/dev/null || true
    for dns in 1.1.1.1 1.0.0.1; do ipcmd -4 rule del priority 1076 iif lo to "$dns/32" lookup "$TABLE" 2>/dev/null || true; done
    for handle in $(nft -a list chain inet fw4 forward 2>/dev/null | sed -n '/comment "fturn-fou-auto-/s/.*# handle \([0-9]*\).*/\1/p'); do nft delete rule inet fw4 forward handle "$handle" 2>/dev/null || true; done
    ipcmd -4 route flush table "$TABLE" 2>/dev/null || true
    if [ -f /var/run/fturn-fou-auto.link ]; then ipcmd link del "$IFACE" 2>/dev/null || true; fi
    if [ -f /var/run/fturn-fou-auto.fou ]; then ipcmd fou del port "$old_port" local "$old_router" 2>/dev/null || true; fi
    rm -f "$STATE" /var/run/fturn-fou-auto.source /var/run/fturn-fou-auto.link /var/run/fturn-fou-auto.fou
    log 'FOU stopped; existing HEV policy restored'
}
probe_dns() { nslookup -timeout=2 example.com 1.1.1.1 >/dev/null 2>&1; }
step() {
    [ -r "$KEY_FILE" ] || { cleanup; return 1; }
    key=$(cat "$KEY_FILE")
    [ "${#key}" -eq 64 ] || { cleanup; return 1; }
    case "$key" in *[!0-9a-fA-F]*) cleanup; return 1;; esac
    upstream=$(/usr/sbin/fturn-hotspot-upstream 2>/dev/null) || { cleanup; return 1; }
    set -- $upstream
    [ "$#" -eq 3 ] || { cleanup; return 1; }
    phone=$1; router=$2
    valid_ip "$phone" && valid_ip "$router" || { cleanup; return 1; }
    response=$(printf 'POST /v1/lease HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n' "$phone" "$key" | "$TIMEOUT" 4 nc "$phone" "$CONTROL_PORT" 2>/dev/null) || { cleanup; return 1; }
    case "$response" in 'HTTP/1.1 200 '*|'HTTP/1.0 200 '*) ;; *) cleanup; return 1;; esac
    body=$(printf '%s\n' "$response" | tr -d '\r' | sed '1,/^$/d')
    version=$(printf '%s' "$body" | jsonfilter -e '@.protocol')
    got_phone=$(printf '%s' "$body" | jsonfilter -e '@.phone')
    got_router=$(printf '%s' "$body" | jsonfilter -e '@.router')
    port=$(printf '%s' "$body" | jsonfilter -e '@.router_port')
    remote_port=$(printf '%s' "$body" | jsonfilter -e '@.port')
    mtu=$(printf '%s' "$body" | jsonfilter -e '@.mtu')
    [ "$version" = 1 ] && [ "$phone" = "$got_phone" ] && [ "$router" = "$got_router" ] && valid_port "$port" && valid_port "$remote_port" || { cleanup; return 1; }
    case "$mtu" in ''|*[!0-9]*) cleanup; return 1;; esac
    [ "$mtu" -ge 576 ] && [ "$mtu" -le 1400 ] || { cleanup; return 1; }
    lan=$(ipcmd -4 route show table main dev br-lan | awk '$1 ~ /^[0-9.]+\/[0-9]+$/ {print $1;exit}')
    lan_ip=$(ipcmd -4 addr show dev br-lan | awk '/inet / {split($2,a,"/");print a[1];exit}')
    valid_ip "$lan_ip" || { cleanup; return 1; }
    prefixes=$(printf '%s' "$body" | jsonfilter -e '@.source_prefixes[*]')
    printf '%s\n' "$prefixes" | grep -Fxq "$lan" || { cleanup; return 1; }
    next="$phone $router $port $remote_port $mtu $lan"
    current=$(cat "$STATE" 2>/dev/null || true)
    if [ "$next" = "$current" ] && ipcmd link show "$IFACE" >/dev/null 2>&1 && nft list chain inet fw4 forward | grep -q 'comment "fturn-fou-auto-out"'; then return 0; fi
    cleanup
    if ipcmd link show "$IFACE" >/dev/null 2>&1; then log "Refusing to replace an unowned interface"; return 1; fi
    # Record ownership before the first mutation so any later failure rolls it back.
    umask 077
    printf '%s\n' "$next" > "$STATE"
    printf '%s\n' "$TEST_SOURCE" > /var/run/fturn-fou-auto.source
    ipcmd fou add port "$port" ipproto 4 local "$router" || { cleanup; return 1; }
    touch /var/run/fturn-fou-auto.fou
    ipcmd link add "$IFACE" type ipip remote "$phone" local "$router" ttl 64 encap fou encap-sport "$port" encap-dport "$remote_port" || { cleanup; return 1; }
    touch /var/run/fturn-fou-auto.link
    ipcmd link set "$IFACE" mtu "$mtu" up || { cleanup; return 1; }
    ipcmd -4 route add default dev "$IFACE" src "$lan_ip" table "$TABLE" metric 10 || { cleanup; return 1; }
    ipcmd -4 route add unreachable default table "$TABLE" metric 32760 || { cleanup; return 1; }
    # A dedicated firewall rule permits only our LAN prefix to use this interface.
    nft -f - <<EOF || { cleanup; return 1; }
insert rule inet fw4 forward iifname "br-lan" oifname "$IFACE" ip saddr $lan counter accept comment "fturn-fou-auto-out"
insert rule inet fw4 forward iifname "$IFACE" oifname "br-lan" ip daddr $lan ct state established,related counter accept comment "fturn-fou-auto-return"
EOF
    ipcmd -4 rule add priority 1077 to "$phone/32" iif br-lan lookup main || { cleanup; return 1; }
    for dns in 1.1.1.1 1.0.0.1; do ipcmd -4 rule add priority 1076 iif lo to "$dns/32" lookup "$TABLE" || { cleanup; return 1; }; done
    # Do not switch clients until the actual packet path returns a DNS response.
    probe_dns || { cleanup; return 1; }
    if [ -n "$TEST_SOURCE" ]; then
        valid_ip "$TEST_SOURCE" && ipcmd -4 rule add priority 1078 from "$TEST_SOURCE/32" iif br-lan lookup "$TABLE" || { cleanup; return 1; }
    else
        ipcmd -4 rule add priority 1078 iif br-lan lookup "$TABLE" || { cleanup; return 1; }
    fi
    log "FOU ready: phone=$phone router=$router MTU=$mtu"
}
mkdir -p /var/lock
exec 9>/var/lock/fturn-fou-auto.lock
flock -n 9 || exit 0
case "${1:---once}" in
 --stop) cleanup;;
 --once) step;;
 --daemon)
    trap 'cleanup; exit 0' INT TERM HUP
    last_probe=0
    while :; do
        if step && [ -r "$STATE" ]; then
            now=$(cut -d. -f1 /proc/uptime)
            if [ $((now-last_probe)) -ge 15 ]; then probe_dns || cleanup; last_probe=$now; fi
        fi
        sleep 3 & wait $! || true
    done;;
 *) echo 'Usage: fturn-fou-auto --once|--daemon|--stop' >&2; exit 2;;
esac
