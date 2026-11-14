//go:build ignore

// Rootless FOU client used only for acceptance. No credentials are compiled in.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/transport/wireguard"
	M "github.com/sagernet/sing/common/metadata"
)

func main() {
	local := flag.String("local", "", "local IPv4:UDP-port (the configured peer)")
	remote := flag.String("remote", "", "phone IPv4:UDP-port")
	flag.Parse()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	target := netip.MustParseAddrPort(*remote)
	c, e := net.ListenUDP("udp4", net.UDPAddrFromAddrPort(netip.MustParseAddrPort(*local)))
	if e != nil {
		panic(e)
	}
	defer c.Close()
	var lock sync.RWMutex
	devices := map[netip.Addr]wireguard.Device{}
	go func() {
		b := make([]byte, 65535)
		for {
			n, from, err := c.ReadFromUDPAddrPort(b)
			if err != nil {
				return
			}
			if from != target || n < 20 || b[0]>>4 != 4 {
				continue
			}
			dst := netip.AddrFrom4([4]byte(b[16:20]))
			lock.RLock()
			d := devices[dst]
			lock.RUnlock()
			if d != nil {
				d.Write([][]byte{b[:n]}, 0)
			}
		}
	}()
	var wg sync.WaitGroup
	var output sync.Mutex
	failures := 0
	report := func(name string, err error) {
		output.Lock()
		defer output.Unlock()
		r := map[string]any{"check": name, "pass": err == nil}
		if err != nil {
			failures++
			r["error"] = err.Error()
		}
		b, _ := json.Marshal(r)
		fmt.Println(string(b))
	}
	for host := 7; host <= 8; host++ {
		source := netip.AddrFrom4([4]byte{192, 168, 1, byte(host)})
		d, err := wireguard.NewDevice(wireguard.DeviceOptions{Context: ctx, Logger: log.NewNOPFactory().Logger(), MTU: 1280, Address: []netip.Prefix{netip.PrefixFrom(source, 32)}})
		if err != nil {
			panic(err)
		}
		if err = d.Start(); err != nil {
			panic(err)
		}
		lock.Lock()
		devices[source] = d
		lock.Unlock()
		defer d.Close()
		go func(d wireguard.Device) {
			b := make([]byte, 1280)
			sizes := []int{0}
			for {
				_, err := d.Read([][]byte{b}, sizes, 0)
				if err != nil {
					return
				}
				if _, err = c.WriteToUDPAddrPort(b[:sizes[0]], target); err != nil {
					return
				}
			}
		}(d)
		wg.Add(1)
		go func(host int, d wireguard.Device) {
			defer wg.Done()
			for _, network := range []string{"udp", "tcp"} {
				conn, err := d.DialContext(ctx, network, M.SocksaddrFrom(netip.MustParseAddr("1.1.1.1"), 53))
				if err == nil {
					request := new(dns.Msg)
					request.SetQuestion("example.com.", dns.TypeA)
					client := &dns.Client{Net: network, Timeout: 10 * time.Second}
					var response *dns.Msg
					response, _, err = client.ExchangeWithConn(request, &dns.Conn{Conn: conn})
					if err == nil && (response.Id != request.Id || response.Rcode != dns.RcodeSuccess || len(response.Answer) == 0) {
						err = fmt.Errorf("invalid DNS response")
					}
					conn.Close()
				}
				report(fmt.Sprintf("host%d DNS %s", host, network), err)
			}
			transport := &http.Transport{DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
				name, port, _ := net.SplitHostPort(address)
				ip, err := net.DefaultResolver.LookupNetIP(ctx, "ip4", name)
				if err != nil {
					return nil, err
				}
				if len(ip) == 0 {
					return nil, fmt.Errorf("no IPv4 address")
				}
				destination := M.ParseSocksaddr(net.JoinHostPort(ip[0].String(), port))
				return d.DialContext(ctx, network, destination)
			}, TLSHandshakeTimeout: 10 * time.Second, DisableKeepAlives: true}
			defer transport.CloseIdleConnections()
			client := &http.Client{Transport: transport, Timeout: 20 * time.Second}
			response, err := client.Get("https://example.com/")
			if err == nil {
				body, readErr := io.ReadAll(io.LimitReader(response.Body, 65536))
				response.Body.Close()
				err = readErr
				if err == nil && (response.StatusCode != 200 || !strings.Contains(string(body), "Example Domain")) {
					err = fmt.Errorf("unexpected HTTPS response: %d", response.StatusCode)
				}
			}
			report(fmt.Sprintf("host%d HTTPS", host), err)
		}(host, d)
	}
	wg.Wait()
	if failures > 0 {
		os.Exit(1)
	}
}
