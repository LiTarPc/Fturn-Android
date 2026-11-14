package fturnfou

import (
	"context"
	"encoding/binary"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/transport/wireguard"
	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

type echoRouter struct{ adapter.Router }

func (e *echoRouter) PreMatch(adapter.InboundContext, []byte) adapter.PreMatchResult {
	return adapter.PreMatchResult{}
}
func (e *echoRouter) RouteConnectionEx(ctx context.Context, c net.Conn, m adapter.InboundContext, closed N.CloseHandlerFunc) {
	go func() {
		defer c.Close()
		if closed != nil {
			defer closed(nil)
		}
		io.Copy(c, c)
	}()
}
func (e *echoRouter) RoutePacketConnectionEx(ctx context.Context, c N.PacketConn, m adapter.InboundContext, closed N.CloseHandlerFunc) {
	go func() {
		defer c.Close()
		if closed != nil {
			defer closed(nil)
		}
		for {
			b := buf.NewPacket()
			d, err := c.ReadPacket(b)
			if err != nil {
				b.Release()
				return
			}
			if err = c.WritePacket(b, d); err != nil {
				return
			}
		}
	}()
}
func packet(src string) []byte {
	b := make([]byte, 28)
	b[0] = 0x45
	b[8] = 64
	b[9] = 17
	binary.BigEndian.PutUint16(b[2:4], 28)
	a := netip.MustParseAddr(src).As4()
	copy(b[12:16], a[:])
	copy(b[16:20], []byte{203, 0, 113, 1})
	binary.BigEndian.PutUint16(b[24:26], 8)
	fixChecksum(b)
	return b
}
func fixChecksum(b []byte) {
	b[10] = 0
	b[11] = 0
	var s uint32
	for n := 0; n < int(b[0]&15)*4; n += 2 {
		s += uint32(binary.BigEndian.Uint16(b[n : n+2]))
	}
	for s>>16 != 0 {
		s = (s & 65535) + (s >> 16)
	}
	binary.BigEndian.PutUint16(b[10:12], ^uint16(s))
}
func TestPacketValidation(t *testing.T) {
	prefixes := []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}
	if !validPacket(packet("192.168.1.7"), 1280, prefixes) {
		t.Fatal("valid packet rejected")
	}
	cases := map[string]func([]byte) []byte{
		"short": func(b []byte) []byte { return b[:19] }, "version": func(b []byte) []byte { b[0] = 0x65; return b },
		"ihl": func(b []byte) []byte { b[0] = 0x4f; return b }, "total": func(b []byte) []byte { b[3]++; return b },
		"checksum": func(b []byte) []byte { b[10] ^= 1; return b }, "ttl": func(b []byte) []byte { b[8] = 0; fixChecksum(b); return b },
		"source": func(b []byte) []byte { return packet("10.0.0.2") }, "oversize": func(b []byte) []byte { return append(b, make([]byte, 1281)...) }}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			if validPacket(f(packet("192.168.1.7")), 1280, prefixes) {
				t.Fatal("malformed packet accepted")
			}
		})
	}
	// Fragments remain IPv4 packets and are handed to gVisor for reassembly.
	b := packet("192.168.1.7")
	b[6] = 0x20
	fixChecksum(b)
	if !validPacket(b, 1280, prefixes) {
		t.Fatal("fragment rejected")
	}
}
func freeUDP(t *testing.T) *net.UDPConn {
	t.Helper()
	c, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { c.Close() })
	return c
}
func TestBidirectionalTCPAndUDPMultipleFlows(t *testing.T) {
	for _, automatic := range []bool{false, true} {
		name := "manual"
		if automatic {
			name = "automatic"
		}
		t.Run(name, func(t *testing.T) { testBidirectionalFlows(t, automatic) })
	}
}
func testBidirectionalFlows(t *testing.T, automatic bool) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	peer := freeUDP(t)
	serverPort := freeUDP(t)
	port := serverPort.LocalAddr().(*net.UDPAddr).Port
	serverPort.Close()
	o := Options{Listen: "127.0.0.1", ListenPort: uint16(port), Peer: "127.0.0.1", PeerPort: uint16(peer.LocalAddr().(*net.UDPAddr).Port), MTU: 1280, SourcePrefixes: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}}
	if automatic {
		control, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		o.ControlPort = uint16(control.Addr().(*net.TCPAddr).Port)
		control.Close()
		o.Automatic = true
		o.PairingKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	}
	factory := log.NewNOPFactory()
	native, e := NewInbound(ctx, &echoRouter{}, factory.Logger(), "test", o)
	if e != nil {
		t.Fatal(e)
	}
	server := native.(*Inbound)
	if e = server.start(); e != nil {
		t.Fatal(e)
	}
	defer server.Close()
	if automatic {
		server.lease.Store(&peerLease{peer: peer.LocalAddr().(*net.UDPAddr).AddrPort(), local: netip.MustParseAddr("127.0.0.1"), expires: time.Now().Add(autoLeaseDuration)})
	}
	client, e := wireguard.NewDevice(wireguard.DeviceOptions{Context: ctx, Logger: factory.Logger(), MTU: 1280, Address: []netip.Prefix{netip.MustParsePrefix("192.168.1.7/32")}})
	if e != nil {
		t.Fatal(e)
	}
	if e = client.Start(); e != nil {
		t.Fatal(e)
	}
	defer client.Close()
	target := netip.AddrPortFrom(netip.MustParseAddr("127.0.0.1"), uint16(port))
	go func() {
		b := make([]byte, 1280)
		sizes := []int{0}
		for {
			_, err := client.Read([][]byte{b}, sizes, 0)
			if err != nil {
				return
			}
			if _, err = peer.WriteToUDPAddrPort(b[:sizes[0]], target); err != nil {
				return
			}
		}
	}()
	go func() {
		b := make([]byte, 65535)
		for {
			n, _, err := peer.ReadFromUDPAddrPort(b)
			if err != nil {
				return
			}
			if _, err = client.Write([][]byte{b[:n]}, 0); err != nil {
				return
			}
		}
	}()
	// Concurrent TCP/UDP flows to distinct destinations must return to the right socket.
	var wg sync.WaitGroup
	for host := 10; host < 13; host++ {
		for _, network := range []string{"tcp", "udp"} {
			wg.Add(1)
			go func(h int, network string) {
				defer wg.Done()
				// Separate destination avoids relying on mutable client bind configuration.
				destination := M.SocksaddrFrom(netip.AddrFrom4([4]byte{203, 0, 113, byte(h)}), 12345)
				dialCtx, stop := context.WithTimeout(ctx, 5*time.Second)
				defer stop()
				c, err := client.DialContext(dialCtx, network, destination)
				if err != nil {
					t.Error(network, err)
					return
				}
				defer c.Close()
				c.SetDeadline(time.Now().Add(5 * time.Second))
				message := []byte(network + destination.String())
				if _, err = c.Write(message); err != nil {
					t.Error(err)
					return
				}
				got := make([]byte, len(message))
				if _, err = io.ReadFull(c, got); err != nil {
					t.Error(network, err)
					return
				}
				if string(got) != string(message) {
					t.Errorf("crossed response %s: %q", network, got)
				}
			}(host, network)
		}
	}
	wg.Wait()
	// Unknown outer peer must not inject a valid packet into the stack.
	rogue := freeUDP(t)
	rogue.WriteToUDPAddrPort(packet("192.168.1.7"), target)
	deadline := time.Now().Add(time.Second)
	for server.Counters.Rejected.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if server.Counters.RX.Load() == 0 || server.Counters.TX.Load() == 0 || server.Counters.Rejected.Load() == 0 {
		t.Fatal("missing receive, reply or peer rejection")
	}
	// Stop idle UDP receive and stack read loops; repeat close must stay safe.
	stopped := make(chan struct{})
	go func() { server.Close(); server.Close(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(3 * time.Second):
		t.Fatal("close left blocked workers")
	}
	peer.Close()
}

func TestCloseBeforeStartDoesNotOpenListener(t *testing.T) {
	reservation := freeUDP(t)
	port := uint16(reservation.LocalAddr().(*net.UDPAddr).Port)
	reservation.Close()
	value, err := NewInbound(context.Background(), &echoRouter{}, log.NewNOPFactory().Logger(), "cancelled", Options{Listen: "127.0.0.1", ListenPort: port, Peer: "127.0.0.1", PeerPort: 40081, MTU: 1280, SourcePrefixes: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}})
	if err != nil {
		t.Fatal(err)
	}
	server := value.(*Inbound)
	server.Close()
	if err = server.start(); err == nil {
		t.Fatal("closed receiver started")
	}
	check, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)})
	if err != nil {
		t.Fatal("listener leaked", err)
	}
	check.Close()
}
func TestConcurrentStartAndCloseReleasesPort(t *testing.T) {
	reservation := freeUDP(t)
	port := uint16(reservation.LocalAddr().(*net.UDPAddr).Port)
	reservation.Close()
	value, err := NewInbound(context.Background(), &echoRouter{}, log.NewNOPFactory().Logger(), "cancelled", Options{Listen: "127.0.0.1", ListenPort: port, Peer: "127.0.0.1", PeerPort: 40081, MTU: 1280, SourcePrefixes: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}})
	if err != nil {
		t.Fatal(err)
	}
	server := value.(*Inbound)
	var wg sync.WaitGroup
	wg.Add(2)
	start := make(chan struct{})
	go func() { defer wg.Done(); <-start; _ = server.start() }()
	go func() { defer wg.Done(); <-start; server.Close() }()
	close(start)
	wg.Wait()
	server.Close()
	check, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: int(port)})
	if err != nil {
		t.Fatal("listener leaked", err)
	}
	check.Close()
}
