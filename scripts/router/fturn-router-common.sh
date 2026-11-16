#!/bin/sh
# Shared by the OpenWrt controller, discovery helper and installer.
PATH=${FTURN_PATH:-/usr/sbin:/usr/bin:/sbin:/bin}
ROOT=${FTURN_ROOT:-}
CONF=$ROOT/etc/fturn-router
RUN=$ROOT/var/run/fturn-router
STATE=$RUN/state
IFACE=ftfouauto
TABLE=40081
IP=${FTURN_FOU_IP:-ip}
TIMEOUT=${FTURN_FOU_TIMEOUT:-timeout}
[ ! -x /usr/libexec/ip-full ] || IP=${FTURN_FOU_IP:-/usr/libexec/ip-full}
log() { logger -t fturn-fou-auto "$*"; }
fail() { printf '%s\n' "Fturn: $*" >&2; return 1; }
ipcmd() { "$IP" "$@"; }
service_ctl() {
    if [ -n "${FTURN_SERVICE_CMD:-}" ]; then "$FTURN_SERVICE_CMD" "$@"
    else service_name=$1; shift; "$ROOT/etc/init.d/$service_name" "$@"; fi
}
valid_name() { case "$1" in ''|*[!a-zA-Z0-9_.:-]*) return 1;; esac; }
valid_ip() { printf '%s\n' "$1" | awk -F. 'NF!=4 {exit 1} {for(i=1;i<=4;i++) if($i!~/^[0-9]+$/ || $i>255) exit 1}'; }
private_ip() { valid_ip "$1" || return 1; case "$1" in 10.*|192.168.*) return 0;; 172.*) n=${1#172.}; n=${n%%.*}; [ "$n" -ge 16 ] && [ "$n" -le 31 ];; *) return 1;; esac; }
valid_port() { case "$1" in ''|*[!0-9]*) return 1;; esac; [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]; }
valid_key() { [ "${#1}" -eq 64 ] || return 1; case "$1" in *[!0-9a-fA-F]*) return 1;; esac; }
setting() { [ ! -r "$CONF/$1" ] || cat "$CONF/$1"; }
usb_device() {
    dev=$1
    valid_name "$dev" || return 1
    driver=$(readlink "$ROOT/sys/class/net/$dev/device/driver" 2>/dev/null) || return 1
    case "${driver##*/}" in rndis_host|cdc_ncm|cdc_ether|cdc_eem|huawei_cdc_ncm) return 0;; *) return 1;; esac
}
find_usb() {
    explicit=$(setting usb_device)
    if [ -n "$explicit" ]; then usb_device "$explicit" && printf '%s\n' "$explicit"; return; fi
    found=
    for path in "$ROOT"/sys/class/net/*; do
        dev=${path##*/}
        usb_device "$dev" || continue
        [ -z "$found" ] || { fail 'Several USB network devices; choose usb_device in /etc/fturn-router.'; return 1; }
        found=$dev
    done
    [ -n "$found" ] || return 1
    printf '%s\n' "$found"
}
# Derive the actual LAN device and canonical prefix from netifd + connected route.
find_lan() {
    wanted=$(setting lan_network)
    dump=$(ubus call network.interface dump 2>/dev/null) || return 1
    names=$(printf '%s' "$dump" | jsonfilter -e '@.interface[*].interface')
    if [ -z "$wanted" ]; then
        for name in $names; do [ "$name" != lan ] || wanted=lan; done
    fi
    found=; count=0; ssh_lan=
    ssh_server=$(printf '%s' "${SSH_CONNECTION:-}" | awk '{print $3}')
    for name in $names; do
        valid_name "$name" || continue
        [ -z "$wanted" ] || [ "$name" = "$wanted" ] || continue
        status=$(ubus call "network.interface.$name" status 2>/dev/null) || continue
        [ "$(printf '%s' "$status" | jsonfilter -e '@.up')" = true ] || continue
        dev=$(printf '%s' "$status" | jsonfilter -e '@.l3_device')
        valid_name "$dev" || continue
        address=$(ipcmd -4 addr show dev "$dev" | awk '/inet / {split($2,a,"/");print a[1];exit}')
        private_ip "$address" || continue
        if [ -z "$wanted" ] && [ ! -d "$ROOT/sys/class/net/$dev/bridge" ] && [ "$address" != "$ssh_server" ]; then continue; fi
        prefix=$(ipcmd -4 route show table main dev "$dev" | awk '$1 ~ /^[0-9.]+\/[0-9]+$/ {print $1;exit}')
        [ -n "$prefix" ] || continue
        count=$((count+1))
        found="$name $dev $prefix $address"
        [ "$address" != "$ssh_server" ] || ssh_lan=$found
    done
    if [ -n "$ssh_lan" ]; then found=$ssh_lan
    elif [ "$count" -gt 1 ]; then fail 'Several LAN bridges; choose lan_network in /etc/fturn-router.'; return 1; fi
    [ -n "$found" ] || return 1
    printf '%s\n' "$found"
}
# Reuse an existing DHCP network; otherwise netifd owns an ephemeral DHCP client.
find_upstream() {
    usb=$(find_usb) || return 1
    status=$(ipcmd -4 route show dev "$usb")
    phone=$(printf '%s\n' "$status" | awk '$1=="default" && $2=="via" {print $3;exit}')
    if ! private_ip "$phone"; then
        dynamic=$(ubus call network.interface.fturn_usb status 2>/dev/null || true)
        phone=$(printf '%s' "$dynamic" | jsonfilter -e '@.data.dhcpserver')
    fi
    router=$(ipcmd -4 addr show dev "$usb" | awk '/inet / {split($2,a,"/");print a[1];exit}')
    if ! private_ip "$phone" || ! private_ip "$router"; then
        if [ ! -r "$RUN/dhcp-device" ] || [ "$(cat "$RUN/dhcp-device")" != "$usb" ]; then
            mkdir -p "$RUN"
            # No edits to the user's /etc/config/network or existing WAN.
            ubus call network add_dynamic "{\"name\":\"fturn_usb\",\"device\":\"$usb\",\"ifname\":\"$usb\",\"proto\":\"dhcp\",\"defaultroute\":false,\"peerdns\":false}" >/dev/null || return 1
            printf '%s\n' "$usb" > "$RUN/dhcp-device"
        fi
        return 1
    fi
    index=$(cat "$ROOT/sys/class/net/$usb/ifindex") || return 1
    case "$index" in ''|*[!0-9]*) return 1;; esac
    printf '%s %s %s\n' "$phone" "$router" "$index"
}
firewall_backend() {
    if command -v nft >/dev/null && nft list chain inet fw4 forward >/dev/null 2>&1; then echo nft
    elif command -v iptables >/dev/null && iptables -S FORWARD >/dev/null 2>&1; then echo iptables
    else fail 'Neither fw4/nftables nor fw3/iptables FORWARD is available.'; return 1; fi
}
firewall_add() {
    if [ "$backend" = nft ]; then
        nft -f - <<EOF
insert rule inet fw4 forward iifname "$lan_dev" oifname "$IFACE" ip saddr $lan counter accept comment "fturn-fou-auto-out"
insert rule inet fw4 forward iifname "$IFACE" oifname "$lan_dev" ip daddr $lan ct state established,related counter accept comment "fturn-fou-auto-return"
EOF
    else
        iptables -I FORWARD 1 -i "$lan_dev" -o "$IFACE" -s "$lan" -m comment --comment fturn-fou-auto-out -j ACCEPT &&
        iptables -I FORWARD 1 -i "$IFACE" -o "$lan_dev" -d "$lan" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment fturn-fou-auto-return -j ACCEPT
    fi
}
firewall_ready() {
    if [ "$backend" = nft ]; then nft list chain inet fw4 forward 2>/dev/null | grep -q 'comment "fturn-fou-auto-out"'
    else iptables -C FORWARD -i "$lan_dev" -o "$IFACE" -s "$lan" -m comment --comment fturn-fou-auto-out -j ACCEPT 2>/dev/null; fi
}
cleanup() (
    [ -r "$STATE" ] || return 0
    read -r phone router port remote mtu lan lan_dev lan_ip backend < "$STATE" || return 1
    private_ip "$phone" && private_ip "$router" && valid_port "$port" && valid_name "$lan_dev" || return 1
    ipcmd -4 rule del priority 1078 iif "$lan_dev" lookup "$TABLE" 2>/dev/null || true
    ipcmd -4 rule del priority 1077 to "$phone/32" iif "$lan_dev" lookup main 2>/dev/null || true
    for dns in 1.1.1.1 1.0.0.1; do ipcmd -4 rule del priority 1076 iif lo to "$dns/32" lookup "$TABLE" 2>/dev/null || true; done
    if [ "$backend" = nft ]; then
        for handle in $(nft -a list chain inet fw4 forward 2>/dev/null | sed -n '/comment "fturn-fou-auto-/s/.*# handle \([0-9]*\).*/\1/p'); do nft delete rule inet fw4 forward handle "$handle" 2>/dev/null || true; done
    elif [ "$backend" = iptables ]; then
        iptables -D FORWARD -i "$lan_dev" -o "$IFACE" -s "$lan" -m comment --comment fturn-fou-auto-out -j ACCEPT 2>/dev/null || true
        iptables -D FORWARD -i "$IFACE" -o "$lan_dev" -d "$lan" -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment fturn-fou-auto-return -j ACCEPT 2>/dev/null || true
    fi
    ipcmd -4 route flush table "$TABLE" 2>/dev/null || true
    [ ! -f "$RUN/link" ] || ipcmd link del "$IFACE" 2>/dev/null || true
    [ ! -f "$RUN/fou" ] || ipcmd fou del port "$port" local "$router" 2>/dev/null || true
    rm -f "$STATE" "$RUN/link" "$RUN/fou" "$RUN/deadline"
    log 'FOU stopped; previous routing restored'
)
