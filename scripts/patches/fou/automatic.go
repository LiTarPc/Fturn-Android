// Copyright (C) 2026 Fturn contributors. SPDX-License-Identifier: GPL-3.0-or-later
package fturnfou

import (
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"golang.org/x/net/netutil"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"
)

const autoLeaseDuration = 30 * time.Second

type peerLease struct {
	prefixes []netip.Prefix
	peer     netip.AddrPort
	local    netip.Addr
	expires  time.Time
}
type autoControl struct {
	server   *http.Server
	listener net.Listener
	done     chan struct{}
	mu       sync.Mutex
}

func validPairingKey(key string) bool { b, e := hex.DecodeString(key); return e == nil && len(b) == 32 }
func (i *Inbound) currentPeer() (netip.AddrPort, bool) {
	if !i.options.Automatic {
		return i.peer, true
	}
	lease := i.lease.Load()
	if lease == nil || !time.Now().Before(lease.expires) {
		return netip.AddrPort{}, false
	}
	return lease.peer, true
}

// Prefixes are learned only from the authenticated lease owner.
func (i *Inbound) leasePrefixes() []netip.Prefix {
	if i.options.Automatic && i.options.AutoSourcePrefixes {
		if lease := i.lease.Load(); lease != nil {
			return lease.prefixes
		}
	}
	return i.options.SourcePrefixes
}
func parseLANPrefix(value string) (netip.Prefix, bool) {
	p, err := netip.ParsePrefix(value)
	if err != nil || !p.Addr().Is4() || !p.Addr().IsPrivate() || p.Bits() < 8 || p.Masked() != p {
		return netip.Prefix{}, false
	}
	b := p.Addr().As4()
	n := uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3])
	n |= ^uint32(0) >> p.Bits()
	last := netip.AddrFrom4([4]byte{byte(n >> 24), byte(n >> 16), byte(n >> 8), byte(n)})
	return p, last.IsPrivate()
}
func (i *Inbound) startAutoControl() (*autoControl, error) {
	listener, e := net.Listen("tcp4", net.JoinHostPort("0.0.0.0", strconv.Itoa(int(i.options.ControlPort))))
	if e != nil {
		return nil, e
	}
	c := &autoControl{listener: netutil.LimitListener(listener, 8), done: make(chan struct{})}
	c.server = &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { c.mu.Lock(); defer c.mu.Unlock(); i.handleLease(w, r) }), ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 2 * time.Second, WriteTimeout: 2 * time.Second, IdleTimeout: 2 * time.Second, MaxHeaderBytes: 2048}
	go func() { defer close(c.done); _ = c.server.Serve(c.listener) }()
	return c, nil
}
func (c *autoControl) Close() { _ = c.server.Close(); _ = c.listener.Close(); <-c.done }
func (i *Inbound) handleLease(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Connection", "close")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != "POST" || r.URL.Path != "/v1/lease" || r.URL.RawQuery != "" || r.ContentLength != 0 {
		http.Error(w, "bad request", 400)
		return
	}
	actual := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	key, e := hex.DecodeString(actual)
	expected, _ := hex.DecodeString(i.options.PairingKey)
	if !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") || e != nil || len(key) != 32 || subtle.ConstantTimeCompare(key, expected) != 1 {
		http.Error(w, "unauthorized", 401)
		return
	}
	peer, e := netip.ParseAddrPort(r.RemoteAddr)
	local, ok := r.Context().Value(http.LocalAddrContextKey).(net.Addr)
	if e != nil || !ok {
		http.Error(w, "invalid endpoint", 400)
		return
	}
	localAddr, e := netip.ParseAddrPort(local.String())
	if e != nil || !peer.Addr().Is4() || !peer.Addr().IsPrivate() || !localAddr.Addr().Is4() || !localAddr.Addr().IsPrivate() || peer.Addr() == localAddr.Addr() {
		http.Error(w, "private USB endpoints required", 400)
		return
	}
	prefixes := i.options.SourcePrefixes
	if i.options.AutoSourcePrefixes && r.Header.Get("X-Fturn-LAN") != "" {
		prefix, valid := parseLANPrefix(r.Header.Get("X-Fturn-LAN"))
		if !valid {
			http.Error(w, "invalid private LAN prefix", 400)
			return
		}
		prefixes = []netip.Prefix{prefix}
	}
	// A router can renew its own lease. Another peer must wait for expiry.
	previous := i.lease.Load()
	next := netip.AddrPortFrom(peer.Addr(), i.options.PeerPort)
	if previous != nil && time.Now().Before(previous.expires) && (previous.peer != next || previous.local != localAddr.Addr()) {
		http.Error(w, "router already connected", 409)
		return
	}
	i.lease.Store(&peerLease{peer: next, local: localAddr.Addr(), expires: time.Now().Add(autoLeaseDuration), prefixes: prefixes})
	if previous == nil || previous.peer != next || previous.local != localAddr.Addr() || !time.Now().Before(previous.expires) {
		i.logger.Info("FOU auto: router=", next.Addr(), " phone=", localAddr.Addr(), " MTU=", i.options.MTU)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(struct {
		Protocol     int            `json:"protocol"`
		Phone        string         `json:"phone"`
		Router       string         `json:"router"`
		Port         uint16         `json:"port"`
		RouterPort   uint16         `json:"router_port"`
		MTU          uint32         `json:"mtu"`
		Prefixes     []netip.Prefix `json:"source_prefixes"`
		LeaseSeconds int            `json:"lease_seconds"`
	}{1, localAddr.Addr().String(), next.Addr().String(), i.options.ListenPort, i.options.PeerPort, i.options.MTU, prefixes, int(autoLeaseDuration / time.Second)})
}
