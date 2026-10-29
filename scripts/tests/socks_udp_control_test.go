package socks

import (
	"bytes"
	"context"
	"io"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/common/varbin"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

type fturnControlSession struct {
	tcp *net.TCPConn
	udp *net.UDPConn
}

// The real sing client talks to a loopback SOCKS peer. UDP deliberately stays
// open after TCP EOF, proving closure is detected without any ICMP or new packet.
func fturnControlProxy(t *testing.T, command byte) (M.Socksaddr, <-chan *fturnControlSession) {
	t.Helper()
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	sessions := make(chan *fturnControlSession, 16)
	var mu sync.Mutex
	var owned []*fturnControlSession
	var workers sync.WaitGroup
	acceptDone := make(chan struct{})
	go func() {
		defer close(acceptDone)
		for {
			c, e := listener.AcceptTCP()
			if e != nil {
				return
			}
			s := &fturnControlSession{tcp: c}
			mu.Lock()
			owned = append(owned, s)
			mu.Unlock()
			workers.Add(1)
			go func() {
				defer workers.Done()
				c.SetDeadline(time.Now().Add(3 * time.Second))
				r := varbin.StubReader(c)
				if _, e := socks5.ReadAuthRequest(r); e != nil {
					return
				}
				if e := socks5.WriteAuthResponse(c, socks5.AuthResponse{Method: socks5.AuthTypeNotRequired}); e != nil {
					return
				}
				request, e := socks5.ReadRequest(r)
				if e != nil || request.Command != command {
					return
				}
				bind := M.SocksaddrFromNet(listener.Addr())
				if command == socks5.CommandUDPAssociate {
					u, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
					if e != nil {
						return
					}
					mu.Lock()
					s.udp = u
					mu.Unlock()
					bind = M.SocksaddrFromNet(u.LocalAddr())
				}
				if e := socks5.WriteResponse(c, socks5.Response{ReplyCode: socks5.ReplyCodeSuccess, Bind: bind}); e != nil {
					return
				}
				c.SetDeadline(time.Time{})
				sessions <- s
				if command == socks5.CommandUDPAssociate {
					b := make([]byte, 2048)
					for {
						n, from, e := s.udp.ReadFromUDP(b)
						if e != nil {
							return
						}
						if _, e = s.udp.WriteToUDP(b[:n], from); e != nil {
							return
						}
					}
				}
			}()
		}
	}()
	t.Cleanup(func() {
		listener.Close()
		<-acceptDone
		mu.Lock()
		for _, s := range owned {
			s.tcp.Close()
			if s.udp != nil {
				s.udp.Close()
			}
		}
		mu.Unlock()
		workers.Wait()
	})
	return M.SocksaddrFromNet(listener.Addr()), sessions
}

func fturnNextControl(t *testing.T, sessions <-chan *fturnControlSession) *fturnControlSession {
	t.Helper()
	select {
	case s := <-sessions:
		return s
	case <-time.After(3 * time.Second):
		t.Fatal("SOCKS peer handshake stalled")
		return nil
	}
}

func fturnExpectControlClosed(t *testing.T, c net.Conn, readPacket bool) {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	var err error
	if readPacket {
		packet := buf.NewPacket()
		defer packet.Release()
		_, err = c.(*AssociatePacketConn).ReadPacket(packet)
	} else {
		_, err = c.Read(make([]byte, 2048))
	}
	if err == nil {
		t.Fatal("UDP reader remained usable after TCP control ended")
	}
	if timeout, ok := err.(net.Error); ok && timeout.Timeout() {
		t.Fatal("UDP reader only ended on deadline; TCP EOF was not propagated")
	}
}

func TestFturnSocksUDPControlEOFUnblocksReaders(t *testing.T) {
	for _, mode := range []string{"fin", "reset"} {
		for _, readPacket := range []bool{false, true} {
			name := mode + "/Read"
			if readPacket {
				name = mode + "/ReadPacket"
			}
			t.Run(name, func(t *testing.T) {
				address, sessions := fturnControlProxy(t, socks5.CommandUDPAssociate)
				client := NewClient(N.SystemDialer, address, Version5, "", "")
				c, err := client.DialContext(context.Background(), N.NetworkUDP, M.ParseSocksaddr("203.0.113.9:443"))
				if err != nil {
					t.Fatal(err)
				}
				defer c.Close()
				s := fturnNextControl(t, sessions)
				if mode == "fin" {
					s.tcp.CloseWrite()
				} else {
					s.tcp.SetLinger(0)
					s.tcp.Close()
				}
				fturnExpectControlClosed(t, c, readPacket)
			})
		}
	}
}

func TestFturnSocksUDPReopensAfterControlEOF(t *testing.T) {
	address, sessions := fturnControlProxy(t, socks5.CommandUDPAssociate)
	client := NewClient(N.SystemDialer, address, Version5, "", "")
	destination := M.ParseSocksaddr("203.0.113.9:443")
	for i := 0; i < 3; i++ {
		c, err := client.DialContext(context.Background(), N.NetworkUDP, destination)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		s := fturnNextControl(t, sessions)
		c.SetDeadline(time.Now().Add(time.Second))
		payload := []byte{42, byte(i), 99}
		if _, err = c.Write(payload); err != nil {
			t.Fatal(err)
		}
		b := make([]byte, 2048)
		n, err := c.Read(b)
		if err != nil || !bytes.Equal(b[:n], payload) {
			t.Fatalf("association %d exchange: %x %v", i, b[:n], err)
		}
		s.tcp.Close()
		fturnExpectControlClosed(t, c, false)
		c.Close()
		c.Close()
	}
}

type fturnObservedControl struct {
	net.Conn
	done chan struct{}
	once sync.Once
}

func (c *fturnObservedControl) Read(b []byte) (int, error) {
	n, err := c.Conn.Read(b)
	if err != nil {
		c.once.Do(func() { close(c.done) })
	}
	return n, err
}

type fturnObservedDialer struct {
	N.Dialer
	done chan struct{}
}

func (d fturnObservedDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	c, err := d.Dialer.DialContext(ctx, network, destination)
	if err != nil || network != N.NetworkTCP {
		return c, err
	}
	return &fturnObservedControl{Conn: c, done: d.done}, nil
}

func TestFturnSocksUDPExplicitCloseStopsMonitor(t *testing.T) {
	for _, mode := range []string{"client", "client-and-server"} {
		t.Run(mode, func(t *testing.T) {
			address, sessions := fturnControlProxy(t, socks5.CommandUDPAssociate)
			done := make(chan struct{})
			client := NewClient(fturnObservedDialer{Dialer: N.SystemDialer, done: done}, address, Version5, "", "")
			c, err := client.DialContext(context.Background(), N.NetworkUDP, M.ParseSocksaddr("203.0.113.9:443"))
			if err != nil {
				t.Fatal(err)
			}
			s := fturnNextControl(t, sessions)
			var closing sync.WaitGroup
			closing.Add(1)
			go func() { defer closing.Done(); c.Close() }()
			if mode == "client-and-server" {
				closing.Add(1)
				go func() { defer closing.Done(); s.tcp.Close() }()
			}
			closing.Wait()
			c.Close()
			select {
			case <-done:
			case <-time.After(time.Second):
				t.Fatal("TCP control monitor did not stop on explicit Close")
			}
		})
	}
}

func TestFturnSocksTCPConnectPayloadPreserved(t *testing.T) {
	address, sessions := fturnControlProxy(t, socks5.CommandConnect)
	client := NewClient(N.SystemDialer, address, Version5, "", "")
	c, err := client.DialContext(context.Background(), N.NetworkTCP, M.ParseSocksaddr("203.0.113.9:443"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	s := fturnNextControl(t, sessions)
	payload := []byte("TCP response remains application data")
	if _, err = s.tcp.Write(payload); err != nil {
		t.Fatal(err)
	}
	c.SetReadDeadline(time.Now().Add(time.Second))
	received := make([]byte, len(payload))
	if _, err = io.ReadFull(c, received); err != nil || !bytes.Equal(received, payload) {
		t.Fatalf("TCP payload: %q %v", received, err)
	}
}

// The constructor also wraps server-side control streams. Only Client's outbound
// path may consume that stream; changing the shared constructor would steal data.
func TestFturnSocksServerWrapperDoesNotConsumeControl(t *testing.T) {
	udp, err := net.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9})
	if err != nil {
		t.Fatal(err)
	}
	control, peer := net.Pipe()
	defer peer.Close()
	c := NewAssociatePacketConn(udp, M.ParseSocksaddr("203.0.113.9:443"), control)
	defer c.Close()
	written := make(chan error, 1)
	go func() { _, err := peer.Write([]byte{0x42}); written <- err }()
	control.SetReadDeadline(time.Now().Add(time.Second))
	b := make([]byte, 1)
	if _, err = io.ReadFull(control, b); err != nil || b[0] != 0x42 {
		t.Fatalf("server control consumed: %x %v", b, err)
	}
	if err = <-written; err != nil {
		t.Fatal(err)
	}
}
