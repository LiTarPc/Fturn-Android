//go:build ignore

// Rootless router data plane: two synthetic LAN hosts over real UDP FOU.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/transport/wireguard"
	M "github.com/sagernet/sing/common/metadata"
)

func main() {
	local := flag.String("local", "", "PC USB IPv4:40080")
	remote := flag.String("remote", "", "phone USB IPv4:40080")
	flag.Parse()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	peer := netip.MustParseAddrPort(*remote)
	conn, err := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(*local)))
	if err != nil {
		panic(err)
	}
	devices := map[netip.Addr]wireguard.Device{}
	for _, address := range []string{"10.23.4.7", "10.23.4.8"} {
		source := netip.MustParseAddr(address)
		d, e := wireguard.NewDevice(wireguard.DeviceOptions{Context: ctx, Logger: log.NewNOPFactory().Logger(), MTU: 1350, Address: []netip.Prefix{netip.PrefixFrom(source, 32)}})
		if e != nil {
			panic(e)
		}
		if e = d.Start(); e != nil {
			panic(e)
		}
		devices[source] = d
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		b := make([]byte, 65535)
		for {
			n, from, e := conn.ReadFromUDPAddrPort(b)
			if e != nil {
				return
			}
			if from != peer || n < 20 {
				continue
			}
			dst := netip.AddrFrom4([4]byte{b[16], b[17], b[18], b[19]})
			if d := devices[dst]; d != nil {
				_, _ = d.Write([][]byte{b[:n]}, 0)
			}
		}
	}()
	var writers sync.WaitGroup
	for _, d := range devices {
		writers.Add(1)
		go func(d wireguard.Device) {
			defer writers.Done()
			b := make([]byte, 1350)
			sizes := []int{0}
			for {
				_, e := d.Read([][]byte{b}, sizes, 0)
				if e != nil {
					return
				}
				if _, e = conn.WriteToUDPAddrPort(b[:sizes[0]], peer); e != nil {
					return
				}
			}
		}(d)
	}
	failures := 0
	report := func(host, protocol string, e error) {
		r := map[string]any{"host": host, "protocol": protocol, "pass": e == nil}
		if e != nil {
			r["error"] = e.Error()
			failures++
		}
		b, _ := json.Marshal(r)
		fmt.Println(string(b))
	}
	for source, d := range devices {
		target := M.SocksaddrFrom(netip.MustParseAddr("198.51.100.1"), 40553)
		for _, protocol := range []string{"tcp", "udp"} {
			c, e := d.DialContext(ctx, protocol, target)
			if e == nil {
				_ = c.SetDeadline(time.Now().Add(15 * time.Second))
				size := 1200
				if protocol == "tcp" {
					size = 128 * 1024
				}
				payload := make([]byte, size)
				for i := range payload {
					payload[i] = byte((i*13 + 7) % 251)
				}
				received := make([]byte, size)
				repeats := 1
				if protocol == "udp" {
					repeats = 5
				}
				for i := 0; i < repeats && e == nil; i++ {
					_, e = c.Write(payload)
					if e == nil {
						_, e = io.ReadFull(c, received)
					}
					if e == nil && !bytes.Equal(payload, received) {
						e = fmt.Errorf("echo data changed")
					}
				}
				_ = c.Close()
			}
			report(source.String(), protocol, e)
		}
	}
	_ = conn.Close()
	<-done
	for _, d := range devices {
		_ = d.Close()
	}
	writers.Wait()
	if failures != 0 {
		os.Exit(1)
	}
}
