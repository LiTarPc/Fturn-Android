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
