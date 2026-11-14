// Copyright (C) 2026 Fturn contributors. SPDX-License-Identifier: GPL-3.0-or-later
// Experimental, single fixed USB peer; the UDP payload is exactly one IPv4 packet (FOU ipproto 4).
package fturnfou

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/inbound"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing-tun"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const Type = "fturn-fou"

type Options struct {
	Automatic      bool           `json:"automatic"`
	PairingKey     string         `json:"pairing_key"`
	ControlPort    uint16         `json:"control_port"`
	Listen         string         `json:"listen"`
	ListenPort     uint16         `json:"listen_port"`
	Peer           string         `json:"peer"`
	PeerPort       uint16         `json:"peer_port"`
	SourcePrefixes []netip.Prefix `json:"source_prefixes"`
	MTU            uint32         `json:"mtu"`
}

func RegisterInbound(registry *inbound.Registry) {
	inbound.Register[Options](registry, Type, NewInbound)
}

type Counters struct{ RX, TX, RXBytes, TXBytes, Rejected, WriteErrors atomic.Uint64 }

type Inbound struct {
	inbound.Adapter
	ctx       context.Context
	router    adapter.Router
	logger    log.ContextLogger
	options   Options
	peer      netip.AddrPort
	device    wireguard.Device
	conn      *net.UDPConn
	once      sync.Once
	lifecycle sync.Mutex
	closed    bool
	done      chan struct{}
	workers   sync.WaitGroup
	Counters  Counters
	lease     atomic.Pointer[peerLease]
	control   *autoControl
}

func validateOptions(o Options) (netip.AddrPort, error) {
	if o.Automatic {
		if !validPairingKey(o.PairingKey) || o.ControlPort < 1024 || o.ControlPort == o.ListenPort {
			return netip.AddrPort{}, errors.New("FOU auto requires a pairing key and distinct control port >=1024")
		}
		o.Listen = "127.0.0.1"
		o.Peer = "127.0.0.2"
	}
	local, e := netip.ParseAddr(o.Listen)
	if e != nil || !local.Is4() || !(local.IsPrivate() || local.IsLoopback()) || o.ListenPort < 1024 {
		return netip.AddrPort{}, errors.New("FOU requires a concrete private IPv4 listen address and port >=1024")
	}
	peer, e := netip.ParseAddr(o.Peer)
	if e != nil || !peer.Is4() || !(peer.IsPrivate() || peer.IsLoopback()) || o.PeerPort < 1024 || peer == local && o.PeerPort == o.ListenPort {
		return netip.AddrPort{}, errors.New("FOU requires a fixed private IPv4 peer and port >=1024")
	}
	if o.MTU < 576 || o.MTU > 1400 || len(o.SourcePrefixes) == 0 || len(o.SourcePrefixes) > 16 {
		return netip.AddrPort{}, errors.New("FOU requires MTU 576..1400 and 1..16 IPv4 source prefixes")
	}
	for _, p := range o.SourcePrefixes {
		if !p.IsValid() || !p.Addr().Is4() || !p.Addr().IsPrivate() || p.Bits() < 8 || p.Masked() != p {
			return netip.AddrPort{}, errors.New("FOU source prefixes must be canonical private IPv4 networks")
		}
		// A private start alone must not authorize a prefix spanning public addresses.
		last := p.Addr().As4()
		v := binary.BigEndian.Uint32(last[:]) | (uint32(0xffffffff) >> p.Bits())
		var b [4]byte
		binary.BigEndian.PutUint32(b[:], v)
		if !netip.AddrFrom4(b).IsPrivate() {
			return netip.AddrPort{}, errors.New("FOU source prefix includes public addresses")
		}
	}
	return netip.AddrPortFrom(peer, o.PeerPort), nil
}

func NewInbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options Options) (adapter.Inbound, error) {
	peer, err := validateOptions(options)
	if err != nil {
		return nil, err
	}
	i := &Inbound{Adapter: inbound.NewAdapter(Type, tag), ctx: ctx, router: router, logger: logger, options: options, peer: peer, done: make(chan struct{})}
	return i, nil
}

func (i *Inbound) Start(stage adapter.StartStage, scope *adapter.Scope) error {
	if stage != adapter.StartStateStart {
		return nil
	}
	scope.Add(i.Close)
	return i.start()
}
func (i *Inbound) start() error {
	i.lifecycle.Lock()
	defer i.lifecycle.Unlock()
	if i.closed {
		return net.ErrClosed
	}
	if err := i.ctx.Err(); err != nil {
		return err
	}
	listen := i.options.Listen
	if i.options.Automatic {
		listen = "0.0.0.0"
	}
	addr := net.UDPAddrFromAddrPort(netip.AddrPortFrom(netip.MustParseAddr(listen), i.options.ListenPort))
	c, e := net.ListenUDP("udp4", addr)
	if e != nil {
		return fmt.Errorf("FOU listen: %w", e)
	}
	i.conn = c
	// Bound queues: kernel UDP receive queue plus the stack's bounded packet queue.
	if e = c.SetReadBuffer(256 * 1024); e != nil {
		c.Close()
		return e
	}
	deviceOptions := wireguard.DeviceOptions{Context: i.ctx, Logger: i.logger, System: false, Handler: i, MTU: i.options.MTU, UDPTimeout: 2 * time.Minute, UDPNATMax: 4096}
	if manager := service.FromContext[adapter.NetworkManager](i.ctx); manager != nil {
		deviceOptions.InterfaceFinder = manager.InterfaceFinder()
	}
	d, e := wireguard.NewDevice(deviceOptions)
	if e != nil {
		c.Close()
		return e
	}
	i.device = d
	if e = d.Start(); e != nil {
		d.Close()
		c.Close()
		return e
	}
	if i.options.Automatic {
		i.control, e = i.startAutoControl()
		if e != nil {
			d.Close()
			c.Close()
			return e
		}
	}
	i.workers.Add(2)
	go i.receive()
	go i.send()
	i.logger.Info("FOU IPv4 receiver ready, MTU=", i.options.MTU)
	return nil
}

func validPacket(p []byte, mtu uint32, prefixes []netip.Prefix) bool {
	if len(p) < 20 || len(p) > int(mtu) || p[0]>>4 != 4 {
		return false
	}
	h := int(p[0]&15) * 4
	if h < 20 || h > len(p) || int(binary.BigEndian.Uint16(p[2:4])) != len(p) || p[8] == 0 {
		return false
	}
	var sum uint32
	for n := 0; n < h; n += 2 {
		sum += uint32(binary.BigEndian.Uint16(p[n : n+2]))
	}
	for sum>>16 != 0 {
		sum = (sum & 65535) + (sum >> 16)
	}
	if uint16(sum) != 65535 {
		return false
	}
	source := netip.AddrFrom4([4]byte(p[12:16]))
	for _, prefix := range prefixes {
		if prefix.Contains(source) {
			return true
		}
	}
	return false
}
func (i *Inbound) receive() {
	defer i.workers.Done()
	b := make([]byte, 65535)
	for {
		n, peer, e := i.conn.ReadFromUDPAddrPort(b)
		if e != nil {
			return
		}
		expected, ready := i.currentPeer()
		if !ready || peer != expected || !validPacket(b[:n], i.options.MTU, i.options.SourcePrefixes) {
			i.Counters.Rejected.Add(1)
			continue
		}
		// NewDevice.Write synchronously copies packet data into gVisor-owned storage.
		if _, e = i.device.Write([][]byte{b[:n]}, 0); e != nil {
			i.Counters.WriteErrors.Add(1)
			continue
		}
		i.Counters.RX.Add(1)
		i.Counters.RXBytes.Add(uint64(n))
	}
}
func (i *Inbound) send() {
	defer i.workers.Done()
	b := make([]byte, i.options.MTU)
	sizes := []int{0}
	for {
		n, e := i.device.Read([][]byte{b}, sizes, 0)
		if e != nil {
			return
		}
		if n == 0 {
			continue
		}
		peer, ready := i.currentPeer()
		if !ready {
			continue
		}
		if _, e = i.conn.WriteToUDPAddrPort(b[:sizes[0]], peer); e != nil {
			i.Counters.WriteErrors.Add(1)
			continue
		}
		i.Counters.TX.Add(1)
		i.Counters.TXBytes.Add(uint64(sizes[0]))
	}
}
func (i *Inbound) Close() error {
	i.once.Do(func() {
		i.lifecycle.Lock()
		defer i.lifecycle.Unlock()
		i.closed = true
		close(i.done)
		if i.control != nil {
			i.control.Close()
		}
		if i.conn != nil {
			i.conn.Close()
		}
		if i.device != nil {
			i.device.Close()
		}
		i.workers.Wait()
		i.logger.Info("FOU stopped: rx=", i.Counters.RX.Load(), " tx=", i.Counters.TX.Load(), " rx_bytes=", i.Counters.RXBytes.Load(), " tx_bytes=", i.Counters.TXBytes.Load(), " rejected=", i.Counters.Rejected.Load(), " write_errors=", i.Counters.WriteErrors.Load())
	})
	return nil
}
func (i *Inbound) JudgeFlow(network uint8, source, destination netip.AddrPort, first []byte) tun.FlowVerdict {
	return adapter.JudgeFlow(i.router, i.Tag(), Type, network, source, destination, first)
}
func (i *Inbound) NewDNSPacket(payload []byte, source, destination M.Socksaddr, writer N.PacketWriter) {
	i.router.HijackDNSPacket(log.ContextWithNewID(i.ctx), payload, writer, adapter.InboundContext{Inbound: i.Tag(), InboundType: Type, Network: N.NetworkUDP, Source: source, Destination: destination, Protocol: "dns"})
}
func (i *Inbound) NewConnectionEx(ctx context.Context, c net.Conn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	i.router.RouteConnectionEx(log.ContextWithNewID(ctx), c, adapter.InboundContext{Inbound: i.Tag(), InboundType: Type, Source: source, Destination: destination}, onClose)
}
func (i *Inbound) NewPacketConnectionEx(ctx context.Context, c N.PacketConn, source, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	i.router.RoutePacketConnectionEx(log.ContextWithNewID(ctx), c, adapter.InboundContext{Inbound: i.Tag(), InboundType: Type, Source: source, Destination: destination}, onClose)
}
