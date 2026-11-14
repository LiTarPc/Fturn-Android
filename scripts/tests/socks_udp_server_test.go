package socks

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/common/buf"
	SB "github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/protocol/socks/socks5"
)

type fturnServerSocket struct {
	net.PacketConn
	closed chan struct{}
	once   sync.Once
}

func (c *fturnServerSocket) Close() error {
	err := c.PacketConn.Close()
	c.once.Do(func() { close(c.closed) })
	return err
}

type fturnServerListener struct{ sockets chan *fturnServerSocket }

func (l *fturnServerListener) ListenPacket(lc net.ListenConfig, ctx context.Context, network, address string) (net.PacketConn, error) {
	c, err := lc.ListenPacket(ctx, network, address)
	if err != nil {
		return nil, err
	}
	s := &fturnServerSocket{PacketConn: c, closed: make(chan struct{})}
	l.sockets <- s
	return s, nil
}

type fturnServerHandler struct {
	handed         chan N.PacketConn
	done           chan struct{}
	ctx            chan context.Context
	closes         atomic.Int32
	closeAfterEcho atomic.Bool
}

func (*fturnServerHandler) NewConnectionEx(context.Context, net.Conn, M.Socksaddr, M.Socksaddr, N.CloseHandlerFunc) {
	panic("unexpected TCP")
}
func (h *fturnServerHandler) NewPacketConnectionEx(ctx context.Context, c N.PacketConn, source, dest M.Socksaddr, onClose N.CloseHandlerFunc) {
	h.ctx <- ctx
	h.handed <- c
	go func() {
		defer close(h.done)
		defer c.Close()
		defer func() {
			if onClose != nil {
				onClose(nil)
			}
		}()
		for {
			b := buf.NewPacket()
			b.Resize(1024, 0)
			d, err := c.ReadPacket(b)
			if err != nil {
				b.Release()
				return
			}
			if err = c.WritePacket(b, d); err != nil {
				return
			}
			if h.closeAfterEcho.Load() {
				return
			}
		}
	}()
}

type fturnFailResponse struct {
	net.Conn
	writes int
}

func (c *fturnFailResponse) Write(b []byte) (int, error) {
	c.writes++
	if c.writes == 2 {
		return 0, errors.New("injected UDP response failure")
	}
	return c.Conn.Write(b)
}

type fturnServerFixture struct {
	tcp     *net.TCPConn
	server  net.Conn
	cancel  context.CancelFunc
	socket  *fturnServerSocket
	handler *fturnServerHandler
	result  chan error
	bind    M.Socksaddr
}

func fturnServerStart(t *testing.T, failResponse bool) *fturnServerFixture {
	t.Helper()
	listener, err := net.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	tcp, err := net.DialTCP("tcp4", nil, listener.Addr().(*net.TCPAddr))
	if err != nil {
		t.Fatal(err)
	}
	server, err := listener.AcceptTCP()
	if err != nil {
		tcp.Close()
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	pl := &fturnServerListener{sockets: make(chan *fturnServerSocket, 1)}
	h := &fturnServerHandler{handed: make(chan N.PacketConn, 1), done: make(chan struct{}), ctx: make(chan context.Context, 1)}
	f := &fturnServerFixture{tcp: tcp, server: server, cancel: cancel, handler: h, result: make(chan error, 1)}
	t.Cleanup(func() {
		cancel()
		tcp.Close()
		server.Close()
		if f.socket != nil {
			f.socket.Close()
		}
	})
	go func() {
		var c net.Conn = server
		if failResponse {
			c = &fturnFailResponse{Conn: server}
		}
		err := HandleConnectionEx(ctx, c, bufio.NewReader(c), nil, h, pl, 0, M.SocksaddrFromNet(server.RemoteAddr()), func(error) { h.closes.Add(1) })
		if err != nil {
			c.Close()
		}
		f.result <- err
	}()
	tcp.SetDeadline(time.Now().Add(2 * time.Second))
	response, err := ClientHandshake5(tcp, socks5.CommandUDPAssociate, M.ParseSocksaddr("0.0.0.0:0"), "", "")
	if err != nil && !failResponse {
		t.Fatal(err)
	}
	if err == nil && failResponse {
		t.Fatal("injected response failure was ignored")
	}
	tcp.SetDeadline(time.Time{})
	select {
	case f.socket = <-pl.sockets:
	case <-time.After(time.Second):
		t.Fatal("UDP listener was not created")
	}
	f.bind = response.Bind
	return f
}
func fturnServerWait(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(time.Second):
		t.Fatal(what)
	}
}
func fturnServerExchange(t *testing.T, f *fturnServerFixture) *AssociatePacketConn {
	t.Helper()
	udp, err := net.Dial("udp4", f.bind.String())
	if err != nil {
		t.Fatal(err)
	}
	// No outbound control monitor: only the real incoming handler can close its relay.
	c := NewAssociatePacketConn(udp, M.ParseSocksaddr("203.0.113.9:123"), nil)
	t.Cleanup(func() { c.Close() })
	c.SetDeadline(time.Now().Add(time.Second))
	payload := []byte("real UDP exchange")
	if _, err = c.Write(payload); err != nil {
		t.Fatal(err)
	}
	b := make([]byte, 256)
	n, err := c.Read(b)
	if err != nil || !bytes.Equal(b[:n], payload) {
		t.Fatalf("UDP echo: %q %v", b[:n], err)
	}
	return c
}
func TestFturnSocksServerControlBeforeFirstPacket(t *testing.T) {
	for _, mode := range []string{"fin", "reset", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			f := fturnServerStart(t, false)
			switch mode {
			case "fin":
				f.tcp.CloseWrite()
			case "reset":
				f.tcp.SetLinger(0)
				f.tcp.Close()
			case "cancel":
				f.cancel()
			}
			fturnServerWait(t, f.socket.closed, "UDP listener survived control termination before first packet")
			select {
			case err := <-f.result:
				if err == nil {
					t.Fatal("expected interrupted first read")
				}
			case <-time.After(time.Second):
				t.Fatal("first UDP read still blocked")
			}
			select {
			case <-f.handler.handed:
				t.Fatal("closed association handed to router")
			default:
			}
			if f.handler.closes.Load() != 0 {
				t.Fatal("pre-handoff callback belongs to caller")
			}
		})
	}
}
func TestFturnSocksServerControlAfterExchange(t *testing.T) {
	for _, mode := range []string{"fin", "reset", "cancel", "router-close"} {
		t.Run(mode, func(t *testing.T) {
			for cycle := 0; cycle < 3; cycle++ {
				f := fturnServerStart(t, false)
				if mode == "router-close" {
					f.handler.closeAfterEcho.Store(true)
				}
				c := fturnServerExchange(t, f)
				routed := <-f.handler.handed
				ctx := <-f.handler.ctx
				switch mode {
				case "fin":
					f.tcp.CloseWrite()
				case "reset":
					f.tcp.SetLinger(0)
					f.tcp.Close()
				case "cancel":
					f.cancel()
				case "router-close":
					// The router worker closes its cached wrapper after sending the echo.
				}
				fturnServerWait(t, f.socket.closed, "routed UDP listener survived control termination")
				fturnServerWait(t, ctx.Done(), "router context was not cancelled")
				fturnServerWait(t, f.handler.done, "router reader did not stop")
				transport := routed.(*SB.CachedPacketConn).PacketConn.(*fturnServerAssociateConn)
				fturnServerWait(t, transport.monitorDone, "incoming TCP monitor did not stop")
				if f.handler.closes.Load() != 1 {
					t.Fatal("router close callback must run exactly once")
				}
				c.SetDeadline(time.Now().Add(50 * time.Millisecond))
				c.Write([]byte("must not be relayed"))
				if _, err := c.Read(make([]byte, 256)); err == nil {
					t.Fatal("relay accepted new UDP after control closed")
				}
			}
		})
	}
}
func TestFturnSocksServerEarlyErrorsCloseSocket(t *testing.T) {
	t.Run("response-write", func(t *testing.T) {
		f := fturnServerStart(t, true)
		fturnServerWait(t, f.socket.closed, "UDP socket leaked after response write error")
	})
	t.Run("invalid-first-packet", func(t *testing.T) {
		f := fturnServerStart(t, false)
		u, err := net.Dial("udp4", f.bind.String())
		if err != nil {
			t.Fatal(err)
		}
		defer u.Close()
		u.Write([]byte{0})
		fturnServerWait(t, f.socket.closed, "UDP socket leaked after malformed first packet")
	})
}
