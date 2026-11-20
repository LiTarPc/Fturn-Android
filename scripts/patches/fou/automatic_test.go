package fturnfou

import (
	"context"
	"encoding/json"
	"github.com/sagernet/sing-box/log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"testing"
	"time"
)

func TestAutomaticPairingAuthenticationAndLease(t *testing.T) {
	key := strings.Repeat("ab", 32)
	i := &Inbound{options: Options{Automatic: true, PairingKey: key, ListenPort: 40080, PeerPort: 40080, MTU: 1350, SourcePrefixes: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}}, logger: log.NewNOPFactory().Logger()}
	request := func(key, peer, local string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "http://usb/v1/lease", nil)
		r.Header.Set("Authorization", "Bearer "+key)
		r.RemoteAddr = peer
		r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP(local), Port: 40081}))
		w := httptest.NewRecorder()
		i.handleLease(w, r)
		return w
	}
	if _, ok := i.currentPeer(); ok {
		t.Fatal("unpaired receiver must reject all packets")
	}
	if w := request(strings.Repeat("cd", 32), "10.40.221.226:1234", "10.40.221.7"); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if w := request(key, "8.8.8.8:1234", "10.40.221.7"); w.Code != 400 {
		t.Fatal(w.Code)
	}
	w := request(key, "10.40.221.226:1234", "10.40.221.7")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	if strings.Contains(w.Body.String(), key) {
		t.Fatal("pairing key leaked in response")
	}
	var response struct {
		Phone, Router string
		MTU           int
	}
	if e := json.Unmarshal(w.Body.Bytes(), &response); e != nil {
		t.Fatal(e)
	}
	if response.Phone != "10.40.221.7" || response.Router != "10.40.221.226" || response.MTU != 1350 {
		t.Fatal(response)
	}
	if p, ok := i.currentPeer(); !ok || p.String() != "10.40.221.226:40080" {
		t.Fatal(p, ok)
	}
	if w := request(key, "10.41.0.2:1234", "10.41.0.1"); w.Code != 409 {
		t.Fatal("live router replaced", w.Code)
	}
	i.lease.Store(&peerLease{peer: netip.MustParseAddrPort("10.40.221.226:40080"), local: netip.MustParseAddr("10.40.221.7"), expires: time.Now().Add(-time.Second)})
	if _, ok := i.currentPeer(); ok {
		t.Fatal("expired peer remained authorized")
	}
	if w := request(key, "10.41.0.2:1234", "10.41.0.1"); w.Code != 200 {
		t.Fatal("new USB addresses not accepted", w.Code)
	}
	if p, ok := i.currentPeer(); !ok || p.String() != "10.41.0.2:40080" {
		t.Fatal(p, ok)
	}
}
func TestAutomaticOptionsRequireSecretAndSeparatePorts(t *testing.T) {
	o := Options{Automatic: true, PairingKey: strings.Repeat("ab", 32), ListenPort: 40080, PeerPort: 40080, ControlPort: 40081, MTU: 1350, SourcePrefixes: []netip.Prefix{netip.MustParsePrefix("192.168.1.0/24")}}
	if _, e := validateOptions(o); e != nil {
		t.Fatal(e)
	}
	bad := o
	bad.PairingKey = ""
	if _, e := validateOptions(bad); e == nil {
		t.Fatal("missing key accepted")
	}
	bad = o
	bad.ControlPort = bad.ListenPort
	if _, e := validateOptions(bad); e == nil {
		t.Fatal("conflicting port accepted")
	}
}

func TestAuthenticatedAutomaticLANAndLegacyFallback(t *testing.T) {
	key := strings.Repeat("ab", 32)
	fallback := netip.MustParsePrefix("192.168.1.0/24")
	i := &Inbound{options: Options{Automatic: true, AutoSourcePrefixes: true, PairingKey: key, PeerPort: 40080, SourcePrefixes: []netip.Prefix{fallback}}, logger: log.NewNOPFactory().Logger()}
	request := func(header, token string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "http://usb/v1/lease", nil)
		r.Header.Set("Authorization", "Bearer "+token)
		r.Header.Set("X-Fturn-LAN", header)
		r.RemoteAddr = "192.168.42.2:1234"
		r = r.WithContext(context.WithValue(r.Context(), http.LocalAddrContextKey, &net.TCPAddr{IP: net.ParseIP("192.168.42.1"), Port: 40081}))
		w := httptest.NewRecorder()
		i.handleLease(w, r)
		return w
	}
	if w := request("10.23.4.0/24", strings.Repeat("cd", 32)); w.Code != 401 {
		t.Fatal(w.Code)
	}
	if i.lease.Load() != nil {
		t.Fatal("unauthenticated LAN changed lease")
	}
	for _, bad := range []string{"0.0.0.0/0", "8.8.8.0/24", "172.16.0.0/8", "192.168.3.7/24", "fd00::/64"} {
		if w := request(bad, key); w.Code != 400 {
			t.Fatalf("accepted %s: %d", bad, w.Code)
		}
	}
	w := request("10.23.4.0/24", key)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "10.23.4.0/24") {
		t.Fatal(w.Code, w.Body.String())
	}
	if got := i.leasePrefixes(); len(got) != 1 || got[0].String() != "10.23.4.0/24" {
		t.Fatal(got)
	}
	if time.Until(i.lease.Load().expires) < 29*time.Second {
		t.Fatal("insufficient control retry budget")
	}
	if w := request("", key); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if got := i.leasePrefixes(); len(got) != 1 || got[0] != fallback {
		t.Fatal("legacy fallback lost", got)
	}
	i.options.AutoSourcePrefixes = false
	if w := request("10.23.4.0/24", key); w.Code != 200 {
		t.Fatal(w.Code)
	}
	if i.leasePrefixes()[0] != fallback {
		t.Fatal("manual prefix changed")
	}
}
