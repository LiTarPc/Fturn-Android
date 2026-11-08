package socks

import (
	"context"
	"io"
	"net"
	"sync"

	"github.com/sagernet/sing/common/bufio"
	M "github.com/sagernet/sing/common/metadata"
)

// fturnServerAssociateConn owns only the transport, never the router's cached
// first packet. One reader watches the incoming control stream after handshake.
// Outgoing clients have their own monitor; the shared constructor stays passive.
type fturnServerAssociateConn struct {
	*AssociatePacketConn
	cancel      context.CancelFunc
	once        sync.Once
	closeErr    error
	monitorDone chan struct{}
}

func newFturnServerAssociateConn(parent context.Context, udp net.PacketConn, tcp net.Conn, control io.Reader) (context.Context, *fturnServerAssociateConn) {
	ctx, cancel := context.WithCancel(parent)
	c := &fturnServerAssociateConn{
		AssociatePacketConn: NewAssociatePacketConn(bufio.NewServerPacketConn(udp), M.Socksaddr{}, tcp),
		cancel:              cancel,
		monitorDone:         make(chan struct{}),
	}
	stop := context.AfterFunc(ctx, func() { _ = c.Close() })
	go func() {
		defer close(c.monitorDone)
		defer stop()
		_, _ = io.Copy(io.Discard, control)
		_ = c.Close()
	}()
	return ctx, c
}

func (c *fturnServerAssociateConn) Close() error {
	c.once.Do(func() {
		c.cancel()
		c.closeErr = c.AssociatePacketConn.Close()
	})
	return c.closeErr
}
