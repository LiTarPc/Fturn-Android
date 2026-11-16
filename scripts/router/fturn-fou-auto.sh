#!/bin/sh
set -u
LIB=${FTURN_LIB:-/usr/lib/fturn-router}
. "$LIB/fturn-router-common.sh"
umask 077
mkdir -p "$RUN"
now_s() { cut -d. -f1 "$ROOT/proc/uptime" 2>/dev/null || date +%s; }
transient_failure() {
    until=$(cat "$RUN/deadline" 2>/dev/null || echo 0)
    if [ -r "$STATE" ] && [ "$(now_s)" -lt "$until" ]; then
        log 'USB control retry; keeping the still-authorized tunnel'
    else cleanup; fi
    return 1
}
step() {
    key=$(cat "$CONF/key" 2>/dev/null) || { cleanup; return 1; }
    valid_key "$key" || { cleanup; return 1; }
    lan_info=$(find_lan) || { cleanup; return 1; }
    set -- $lan_info
    [ "$#" -eq 4 ] || return 1
    lan_dev=$2; lan=$3; lan_ip=$4
    upstream=$(find_upstream) || { cleanup; return 1; }
    set -- $upstream
    [ "$#" -eq 3 ] || return 1
    phone=$1; router=$2
    response=$(printf 'POST /v1/lease HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nX-Fturn-LAN: %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n' "$phone" "$key" "$lan" | "$TIMEOUT" 3 nc "$phone" 40081 2>/dev/null) || { transient_failure; return 1; }
    case "$response" in
        'HTTP/1.1 200 '*|'HTTP/1.0 200 '*) ;;
        'HTTP/1.1 401 '*|'HTTP/1.1 409 '*|'HTTP/1.0 401 '*|'HTTP/1.0 409 '*) cleanup; log 'Pairing rejected; configure this router in Fturn again'; return 1;;
        *) transient_failure; return 1;;
    esac
    body=$(printf '%s\n' "$response" | tr -d '\r' | sed '1,/^$/d')
    version=$(printf '%s' "$body" | jsonfilter -e '@.protocol')
    got_phone=$(printf '%s' "$body" | jsonfilter -e '@.phone')
    got_router=$(printf '%s' "$body" | jsonfilter -e '@.router')
    port=$(printf '%s' "$body" | jsonfilter -e '@.router_port')
    remote=$(printf '%s' "$body" | jsonfilter -e '@.port')
    mtu=$(printf '%s' "$body" | jsonfilter -e '@.mtu')
    lease=$(printf '%s' "$body" | jsonfilter -e '@.lease_seconds')
    [ "$version" = 1 ] && [ "$phone" = "$got_phone" ] && [ "$router" = "$got_router" ] && valid_port "$port" && valid_port "$remote" || { cleanup; return 1; }
    case "$mtu:$lease" in *[!0-9:]*|:*|*:) cleanup; return 1;; esac
    [ "$mtu" -ge 576 ] && [ "$mtu" -le 1400 ] && [ "$lease" -ge 6 ] && [ "$lease" -le 120 ] || { cleanup; return 1; }
    prefixes=$(printf '%s' "$body" | jsonfilter -e '@.source_prefixes[*]')
    printf '%s\n' "$prefixes" | grep -Fxq "$lan" || { cleanup; log 'LAN prefix not authorized; enable automatic LAN in Fturn'; return 1; }
    backend=$(firewall_backend) || { cleanup; return 1; }
    next="$phone $router $port $remote $mtu $lan $lan_dev $lan_ip $backend"
    if [ "$next" = "$(cat "$STATE" 2>/dev/null)" ] && ipcmd link show "$IFACE" >/dev/null 2>&1 && firewall_ready; then
        printf '%s\n' "$(( $(now_s) + lease - 1 ))" > "$RUN/deadline"
        return 0
    fi
    cleanup
    # These resources are reserved exclusively by this controller, never take over another tunnel.
    if ipcmd link show "$IFACE" >/dev/null 2>&1 || [ -n "$(ipcmd -4 route show table "$TABLE" 2>/dev/null)" ] || ipcmd -4 rule show | grep -Eq '^107[678]:'; then
        fail 'Reserved Fturn interface, table or rule priority is already in use'; return 1
    fi
    printf '%s\n' "$next" > "$STATE"
    printf '%s\n' "$(( $(now_s) + lease - 1 ))" > "$RUN/deadline"
    # A failed add may mean another service owns that object. Mark only successful creations.
    ipcmd fou add port "$port" ipproto 4 local "$router" || { cleanup; return 1; }
    touch "$RUN/fou"
    ipcmd link add "$IFACE" type ipip remote "$phone" local "$router" ttl 64 encap fou encap-sport "$port" encap-dport "$remote" || { cleanup; return 1; }
    touch "$RUN/link"
    ipcmd link set "$IFACE" mtu "$mtu" up || { cleanup; return 1; }
    ipcmd -4 route add default dev "$IFACE" src "$lan_ip" table "$TABLE" metric 10 || { cleanup; return 1; }
    ipcmd -4 route add unreachable default table "$TABLE" metric 32760 || { cleanup; return 1; }
    firewall_add || { cleanup; return 1; }
    ipcmd -4 rule add priority 1077 to "$phone/32" iif "$lan_dev" lookup main || { cleanup; return 1; }
    for dns in 1.1.1.1 1.0.0.1; do ipcmd -4 rule add priority 1076 iif lo to "$dns/32" lookup "$TABLE" || { cleanup; return 1; }; done
    # A bounded initial check; a transient DNS failure never destroys an established tunnel.
    "$TIMEOUT" 2 nslookup example.com 1.1.1.1 >/dev/null 2>&1 || { cleanup; return 1; }
    ipcmd -4 rule add priority 1078 iif "$lan_dev" lookup "$TABLE" || { cleanup; return 1; }
    log "FOU ready: device=$lan_dev LAN=$lan MTU=$mtu firewall=$backend"
}
case "${1:---once}" in
 --stop) cleanup; exit $?;;
 --status) if [ -r "$STATE" ]; then echo 'FOU configured'; else echo 'Waiting for phone/VPN'; fi; exit 0;;
esac
mkdir -p "$ROOT/var/lock"
exec 9>"$ROOT/var/lock/fturn-fou-auto.lock"
flock -n 9 || exit 0
case "${1:---once}" in
 --once) step;;
 --daemon)
    trap 'cleanup; exit 0' INT TERM HUP
    while :; do step || true; sleep 2 & wait $! || true; done;;
 *) fail 'Usage: fturn-fou-auto --once|--daemon|--stop|--status'; exit 2;;
esac
