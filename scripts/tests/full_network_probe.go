package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/binary"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"regexp"
	"strings"
	"sync"
	"time"
)

type result struct {
	Name   string `json:"name"`
	OK     bool   `json:"ok"`
	Millis int64  `json:"millis"`
	Error  string `json:"error,omitempty"`
	Value  any    `json:"value,omitempty"`
}

var proxyAddr, password string

func exact(c io.Reader, n int) ([]byte, error) {
	b := make([]byte, n)
	_, e := io.ReadFull(c, b)
	return b, e
}
func address(host string, port int) []byte {
	var b []byte
	if ip := net.ParseIP(host); ip != nil {
		if v := ip.To4(); v != nil {
			b = append([]byte{1}, v...)
		} else {
			b = append([]byte{4}, ip.To16()...)
		}
	} else {
		b = append([]byte{3, byte(len(host))}, []byte(host)...)
	}
	return binary.BigEndian.AppendUint16(b, uint16(port))
}
func readAddr(c io.Reader, typ byte) (string, int, error) {
	var b []byte
	var e error
	switch typ {
	case 1:
		b, e = exact(c, 4)
	case 4:
		b, e = exact(c, 16)
	case 3:
		var n []byte
		n, e = exact(c, 1)
		if e == nil {
			b, e = exact(c, int(n[0]))
		}
	default:
		e = fmt.Errorf("address type %d", typ)
	}
	if e != nil {
		return "", 0, e
	}
	p, e := exact(c, 2)
	if e != nil {
		return "", 0, e
	}
	host := string(b)
	if typ != 3 {
		host = net.IP(b).String()
	}
	return host, int(binary.BigEndian.Uint16(p)), nil
}
func auth(pwd string) (net.Conn, error) {
	c, e := net.DialTimeout("tcp", proxyAddr, 5*time.Second)
	if e != nil {
		return nil, e
	}
	c.SetDeadline(time.Now().Add(15 * time.Second))
	_, e = c.Write([]byte{5, 1, 2})
	if e != nil {
		c.Close()
		return nil, e
	}
	h, e := exact(c, 2)
	if e != nil || !bytes.Equal(h, []byte{5, 2}) {
		c.Close()
		return nil, fmt.Errorf("auth method: %v %v", h, e)
	}
	user := []byte("fturn")
	c.Write(append(append(append([]byte{1, byte(len(user))}, user...), byte(len(pwd))), []byte(pwd)...))
	h, e = exact(c, 2)
	if e != nil || !bytes.Equal(h, []byte{1, 0}) {
		c.Close()
		return nil, fmt.Errorf("password rejected")
	}
	return c, nil
}
func request(c net.Conn, cmd byte, host string, port int) (string, int, error) {
	_, e := c.Write(append([]byte{5, cmd, 0}, address(host, port)...))
	if e != nil {
		return "", 0, e
	}
	h, e := exact(c, 4)
	if e != nil {
		return "", 0, e
	}
	if h[0] != 5 || h[1] != 0 {
		return "", 0, fmt.Errorf("SOCKS reply %v", h)
	}
	return readAddr(c, h[3])
}
func dial(ctx context.Context, network, target string) (net.Conn, error) {
	if proxyAddr == "" {
		return (&net.Dialer{Timeout: 10 * time.Second, Resolver: resolver()}).DialContext(ctx, network, target)
	}
	host, p, e := net.SplitHostPort(target)
	if e != nil {
		return nil, e
	}
	port := 0
	fmt.Sscan(p, &port)
	c, e := auth(password)
	if e != nil {
		return nil, e
	}
	_, _, e = request(c, 1, host, port)
	if e != nil {
		c.Close()
		return nil, e
	}
	c.SetDeadline(time.Time{})
	return c, nil
}
func resolver() *net.Resolver {
	return &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "udp", "1.1.1.1:53")
	}}
}
func associate() (net.Conn, *net.UDPConn, *net.UDPAddr, error) {
	c, e := auth(password)
	if e != nil {
		return nil, nil, nil, e
	}
	h, p, e := request(c, 3, "0.0.0.0", 0)
	if e != nil {
		c.Close()
		return nil, nil, nil, e
	}
	if h == "0.0.0.0" || h == "::" {
		h, _, _ = net.SplitHostPort(proxyAddr)
	}
	addr, e := net.ResolveUDPAddr("udp4", net.JoinHostPort(h, fmt.Sprint(p)))
	if e != nil {
		c.Close()
		return nil, nil, nil, e
	}
	u, e := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero})
	return c, u, addr, e
}
func unpack(b []byte) ([]byte, error) {
	if len(b) < 4 || !bytes.Equal(b[:3], []byte{0, 0, 0}) {
		return nil, fmt.Errorf("UDP header")
	}
	r := bytes.NewReader(b[4:])
	_, _, e := readAddr(r, b[3])
	if e != nil {
		return nil, e
	}
	return io.ReadAll(r)
}
func udp(host string, port int, q []byte) ([]byte, error) {
	if proxyAddr == "" {
		c, e := net.DialTimeout("udp4", net.JoinHostPort(host, fmt.Sprint(port)), 5*time.Second)
		if e != nil {
			return nil, e
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(8 * time.Second))
		c.Write(q)
		b := make([]byte, 65535)
		n, e := c.Read(b)
		return b[:n], e
	}
	c, u, a, e := associate()
	if e != nil {
		return nil, e
	}
	defer c.Close()
	defer u.Close()
	u.SetDeadline(time.Now().Add(8 * time.Second))
	u.WriteToUDP(append(append([]byte{0, 0, 0}, address(host, port)...), q...), a)
	b := make([]byte, 65535)
	n, _, e := u.ReadFromUDP(b)
	if e != nil {
		return nil, e
	}
	return unpack(b[:n])
}
func dnsQuery(typ uint16) []byte {
	q := make([]byte, 12)
	rand.Read(q[:2])
	binary.BigEndian.PutUint16(q[2:], 0x100)
	binary.BigEndian.PutUint16(q[4:], 1)
	q = append(q, 7)
	q = append(q, []byte("example")...)
	q = append(q, 3)
	q = append(q, []byte("com")...)
	q = append(q, 0)
	q = binary.BigEndian.AppendUint16(q, typ)
	return binary.BigEndian.AppendUint16(q, 1)
}
func dns(network, server string, typ uint16) (any, error) {
	q := dnsQuery(typ)
	var b []byte
	var e error
	if network == "udp" {
		b, e = udp(server, 53, q)
	} else {
		c, err := dial(context.Background(), "tcp", net.JoinHostPort(server, "53"))
		if err != nil {
			return nil, err
		}
		defer c.Close()
		c.SetDeadline(time.Now().Add(8 * time.Second))
		c.Write(append(binary.BigEndian.AppendUint16(nil, uint16(len(q))), q...))
		n, err := exact(c, 2)
		if err != nil {
			return nil, err
		}
		b, e = exact(c, int(binary.BigEndian.Uint16(n)))
	}
	if e != nil {
		return nil, e
	}
	if len(b) < 12 || !bytes.Equal(q[:2], b[:2]) || b[2]&128 == 0 || b[3]&15 != 0 {
		return nil, fmt.Errorf("invalid DNS response")
	}
	answers := binary.BigEndian.Uint16(b[6:])
	if typ == 28 && answers != 0 {
		return nil, fmt.Errorf("IPv6 DNS answers returned")
	}
	if typ == 1 && answers == 0 {
		return nil, fmt.Errorf("no IPv4 DNS answers")
	}
	return map[string]any{"answers": answers}, nil
}
func main() {
	scope := flag.String("scope", "tun", "baseline, tun, socks")
	lanURL := flag.String("lan-check", "", "controlled route URL")
	marker := flag.String("marker", "", "expected controlled response")
	wantLAN := flag.Bool("want-lan", false, "expect direct route")
	egressOnly := flag.Bool("egress-only", false, "only collect public exit IPs")
	socks := flag.String("socks", "", "SOCKS5 address")
	tcpOnly := flag.Bool("tcp-only", false, "skip UDP over ADB")
	flag.Parse()
	proxyAddr = *socks
	password = os.Getenv("FTURN_SOCKS_PASSWORD")
	all := []result{}
	var mu sync.Mutex
	run := func(name string, f func() (any, error)) {
		start := time.Now()
		v, e := f()
		r := result{Name: name, OK: e == nil, Millis: time.Since(start).Milliseconds(), Value: v}
		if e != nil {
			r.Error = e.Error()
		}
		mu.Lock()
		all = append(all, r)
		mu.Unlock()
	}
	client := &http.Client{Transport: &http.Transport{Proxy: nil, DialContext: dial, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}, DisableKeepAlives: true}, Timeout: 20 * time.Second}
	get := func(url string) ([]byte, int, error) {
		res, e := client.Get(url)
		if e != nil {
			return nil, 0, e
		}
		defer res.Body.Close()
		b, e := io.ReadAll(io.LimitReader(res.Body, 1024*1024))
		return b, res.StatusCode, e
	}
	if *lanURL != "" {
		client.Timeout = 5 * time.Second
		run("controlled_bypass_route", func() (any, error) {
			b, s, e := get(*lanURL)
			reached := e == nil && s == 200 && string(b) == *marker
			if reached != *wantLAN {
				return map[string]any{"reached": reached}, fmt.Errorf("controlled route differs from expected")
			}
			return map[string]any{"reached": reached}, nil
		})
		json.NewEncoder(os.Stdout).Encode(map[string]any{"scope": *scope, "uid": os.Getuid(), "checks": all})
		return
	}
	if *egressOnly {
		for _, site := range []struct{ name, url string }{{"foreign_ipv4", "https://api.ipify.org?format=json"}, {"ru_ipv4", "https://ipv4-internet.yandex.net/api/v0/ip"}} {
			v := site
			run(v.name, func() (any, error) {
				b, s, e := get(v.url)
				if e != nil {
					return nil, e
				}
				if s != 200 {
					return s, fmt.Errorf("HTTP %d", s)
				}
				ips := regexp.MustCompile(`[0-9]{1,3}(?:\.[0-9]{1,3}){3}`).FindAllString(string(b), -1)
				for _, ip := range ips {
					if net.ParseIP(ip).To4() != nil {
						return ip, nil
					}
				}
				return nil, fmt.Errorf("no IPv4 in response")
			})
		}
		json.NewEncoder(os.Stdout).Encode(map[string]any{"scope": *scope, "uid": os.Getuid(), "checks": all})
		return
	}
	if *scope != "baseline" {
		run("ipv6_literal_blocked", func() (any, error) {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			defer cancel()
			c, e := dial(ctx, "tcp6", "[2001:4860:4860::8888]:443")
			if e != nil {
				return nil, nil
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(3 * time.Second))
			c.Write([]byte("invalid-test-request"))
			b := make([]byte, 1)
			_, e = c.Read(b)
			if e == nil {
				return nil, fmt.Errorf("IPv6 data escaped")
			}
			return nil, nil
		})
	}
	if proxyAddr != "" {
		run("socks_anonymous_rejected", func() (any, error) {
			c, e := net.DialTimeout("tcp", proxyAddr, 4*time.Second)
			if e != nil {
				return nil, e
			}
			defer c.Close()
			c.SetDeadline(time.Now().Add(4 * time.Second))
			c.Write([]byte{5, 1, 0})
			h, e := exact(c, 2)
			if e != nil {
				return nil, e
			}
			if !bytes.Equal(h, []byte{5, 255}) {
				return nil, fmt.Errorf("anonymous accepted")
			}
			return nil, nil
		})
		run("socks_wrong_password_rejected", func() (any, error) {
			c, e := auth("incorrect-test-password")
			if c != nil {
				c.Close()
			}
			if e == nil {
				return nil, fmt.Errorf("wrong password accepted")
			}
			if !strings.Contains(e.Error(), "password rejected") {
				return nil, e
			}
			return nil, nil
		})
	}
	run("https_example_domain", func() (any, error) {
		b, status, e := get("https://example.com/?fturnqa=" + fmt.Sprint(time.Now().UnixNano()))
		if e != nil {
			return nil, e
		}
		if status != 200 || !bytes.Contains(b, []byte("Example Domain")) {
			return status, fmt.Errorf("unexpected content or status %d", status)
		}
		return status, nil
	})
	run("https_exit_ipv4", func() (any, error) {
		b, status, e := get("https://api.ipify.org?format=json")
		if e != nil {
			return nil, e
		}
		var v struct {
			IP string `json:"ip"`
		}
		if status != 200 || json.Unmarshal(b, &v) != nil || net.ParseIP(v.IP).To4() == nil {
			return nil, fmt.Errorf("invalid IPv4 response")
		}
		return v.IP, nil
	})
	// RU checks retain HTTP status and test reachability. Review 4xx separately as functional failures.
	for _, host := range []string{"https://yandex.ru/internet/", "https://www.pochta.ru/", "https://vk.com/"} {
		url := host
		run("https_ru_"+strings.Split(strings.TrimPrefix(url, "https://"), "/")[0], func() (any, error) {
			b, status, e := get(url)
			if e != nil {
				return nil, e
			}
			if status < 200 || status >= 500 {
				return status, fmt.Errorf("HTTP %d", status)
			}
			return map[string]any{"status": status, "bytes": len(b)}, nil
		})
	}
	if !*tcpOnly {
		for _, netw := range []string{"udp", "tcp"} {
			n := netw
			run(n+"_dns_ipv4", func() (any, error) { return dns(n, "1.1.1.1", 1) })
		}
		if *scope != "baseline" {
			run("dns_hijack_arbitrary_ip", func() (any, error) { return dns("udp", "203.0.113.1", 1) })
			run("dns_aaaa_suppressed", func() (any, error) { return dns("udp", "1.1.1.1", 28) })
		}
		run("udp_stun_non_dns", func() (any, error) {
			ips, e := resolver().LookupIP(context.Background(), "ip4", "stun.l.google.com")
			if e != nil || len(ips) == 0 {
				return nil, fmt.Errorf("STUN DNS failed: %v", e)
			}
			q := make([]byte, 20)
			binary.BigEndian.PutUint16(q, 1)
			binary.BigEndian.PutUint32(q[4:], 0x2112a442)
			rand.Read(q[8:])
			b, e := udp(ips[0].String(), 19302, q)
			if e != nil {
				return nil, e
			}
			if len(b) < 20 || binary.BigEndian.Uint16(b) != 0x101 || !bytes.Equal(q[8:], b[8:20]) {
				return nil, fmt.Errorf("invalid STUN response")
			}
			return nil, nil
		})
		if proxyAddr != "" {
			run("socks_udp_control_close", func() (any, error) {
				c, u, a, e := associate()
				if e != nil {
					return nil, e
				}
				defer c.Close()
				defer u.Close()
				q := dnsQuery(1)
				p := append(append([]byte{0, 0, 0}, address("1.1.1.1", 53)...), q...)
				u.SetReadDeadline(time.Now().Add(8 * time.Second))
				u.WriteToUDP(p, a)
				buf := make([]byte, 65535)
				if _, _, e = u.ReadFromUDP(buf); e != nil {
					return nil, e
				}
				c.Close()
				time.Sleep(300 * time.Millisecond)
				q = dnsQuery(1)
				p = append(append([]byte{0, 0, 0}, address("1.1.1.1", 53)...), q...)
				u.SetReadDeadline(time.Now().Add(time.Second))
				u.WriteToUDP(p, a)
				if _, _, e = u.ReadFromUDP(buf); e == nil {
					return nil, fmt.Errorf("UDP association survived TCP close")
				}
				if n, ok := e.(net.Error); !ok || !n.Timeout() {
					return nil, e
				}
				return nil, nil
			})
		}
	}
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			run(fmt.Sprintf("concurrent_https_%d", i+1), func() (any, error) {
				b, s, e := get("https://example.com/?parallel=" + fmt.Sprint(i))
				if e == nil && (s != 200 || !bytes.Contains(b, []byte("Example Domain"))) {
					e = fmt.Errorf("HTTP/content")
				}
				return s, e
			})
		}(i)
	}
	wg.Wait()
	passed := true
	for _, r := range all {
		if !r.OK {
			passed = false
		}
	}
	json.NewEncoder(os.Stdout).Encode(map[string]any{"scope": *scope, "uid": os.Getuid(), "allPassed": passed, "checks": all})
	if !passed {
		os.Exit(1)
	}
}
