package libbox

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	box "github.com/sagernet/sing-box"
	"golang.org/x/net/proxy"
)

// Tests use configs emitted by the Kotlin parser, with a local byte-stream relay
// instead of a live FreeTurn server. No TUN, public server or user credentials.
func TestFturnVlessRelay(t *testing.T) {
	fixtures := os.Getenv("FTURN_VLESS_FIXTURES")
	if fixtures == "" {
		t.Fatal("Run JVM tests and set FTURN_VLESS_FIXTURES")
	}
	files, err := filepath.Glob(filepath.Join(fixtures, "*.json"))
	if err != nil || len(files) < 11 {
		t.Fatal("Missing production-generated VLESS fixtures")
	}
	certificate, keyPEM := fturnTestCertificate(t)
	pair, err := tls.X509KeyPair([]byte(certificate), []byte(keyPEM))
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		t.Run(strings.TrimSuffix(filepath.Base(file), ".json"), func(t *testing.T) {
			content, err := os.ReadFile(file)
			if err != nil {
				t.Fatal(err)
			}
			var client map[string]any
			if err = json.Unmarshal(content, &client); err != nil {
				t.Fatal(err)
			}
			out := client["outbounds"].([]any)[0].(map[string]any)
			tlsOut, secured := out["tls"].(map[string]any)
			_, reality := tlsOut["reality"]
			flow, _ := out["flow"].(string)
			serverPort, socksPort := fturnFreePort(t), fturnFreePort(t)
			inbound := map[string]any{"type": "vless", "listen": "127.0.0.1", "listen_port": serverPort,
				"users": []any{map[string]any{"uuid": out["uuid"], "flow": flow}}}
			if transport, ok := out["transport"].(map[string]any); ok {
				serverTransport := make(map[string]any)
				for k, v := range transport {
					serverTransport[k] = v
				}
				if serverTransport["type"] == "ws" {
					// Servers match URL.Path; query parameters stay on the client request.
					serverTransport["path"] = strings.Split(serverTransport["path"].(string), "?")[0]
					// The native server otherwise treats query suffixes as path-based early data,
					// even with max_early_data=0. Header mode permits ordinary WS query strings.
					if serverTransport["early_data_header_name"] == nil {
						serverTransport["early_data_header_name"] = "Sec-WebSocket-Protocol"
					}
				}
				inbound["transport"] = serverTransport
			}
			if secured {
				tlsIn := map[string]any{"enabled": true, "server_name": "front.example"}
				if alpn, ok := tlsOut["alpn"]; ok {
					tlsIn["alpn"] = alpn
				}
				if reality {
					cover, err := tls.Listen("tcp4", "127.0.0.1:0", &tls.Config{
						Certificates: []tls.Certificate{pair}, MinVersion: tls.VersionTLS13, NextProtos: []string{"h2", "http/1.1"}})
					if err != nil {
						t.Fatal(err)
					}
					t.Cleanup(func() { cover.Close() })
					go func() {
						for {
							conn, err := cover.Accept()
							if err != nil {
								return
							}
							go func() {
								defer conn.Close()
								conn.SetDeadline(time.Now().Add(5 * time.Second))
								conn.(*tls.Conn).Handshake()
							}()
						}
					}()
					private, err := ecdh.X25519().GenerateKey(rand.Reader)
					if err != nil {
						t.Fatal(err)
					}
					tlsOut["reality"].(map[string]any)["public_key"] = base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes())
					tlsIn["reality"] = map[string]any{"enabled": true, "private_key": base64.RawURLEncoding.EncodeToString(private.Bytes()),
						"short_id":  []string{tlsOut["reality"].(map[string]any)["short_id"].(string)},
						"handshake": map[string]any{"server": "127.0.0.1", "server_port": cover.Addr().(*net.TCPAddr).Port}}
				} else {
					tlsIn["certificate"], tlsIn["key"] = []string{certificate}, []string{keyPEM}
					// Trust the generated CA; never disable hostname verification.
					tlsOut["certificate"] = []string{certificate}
				}
				inbound["tls"] = tlsIn
			}
			server := map[string]any{"log": map[string]any{"disabled": true}, "inbounds": []any{inbound},
				"outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}}
			fturnStartBox(t, server)
			relay := fturnRelay(t, serverPort)
			out["server_port"] = relay
			client["log"] = map[string]any{"disabled": true}
			client["inbounds"] = []any{map[string]any{"type": "socks", "listen": "127.0.0.1", "listen_port": socksPort}}
			delete(client["route"].(map[string]any), "auto_detect_interface")
			fturnStartBox(t, client)
			echo, err := net.Listen("tcp4", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			t.Cleanup(func() { echo.Close() })
			go func() {
				for {
					conn, err := echo.Accept()
					if err != nil {
						return
					}
					go func() { defer conn.Close(); conn.SetDeadline(time.Now().Add(8 * time.Second)); io.Copy(conn, conn) }()
				}
			}()
			dialer, err := proxy.SOCKS5("tcp", net.JoinHostPort("127.0.0.1", fturnPort(socksPort)), nil, &net.Dialer{Timeout: 5 * time.Second})
			if err != nil {
				t.Fatal(err)
			}
			conn, err := dialer.Dial("tcp", echo.Addr().String())
			expectedFailure := strings.Contains(filepath.Base(file), "wrong-sni")
			if err != nil {
				if expectedFailure {
					return
				}
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(8 * time.Second))
			data := bytes.Repeat([]byte("FreeTurn relay VLESS transport test\n"), 1024)
			if _, err = conn.Write(data); err != nil {
				if expectedFailure {
					return
				}
				t.Fatal(err)
			}
			received := make([]byte, len(data))
			_, err = io.ReadFull(conn, received)
			if expectedFailure {
				if err == nil {
					t.Fatal("Wrong TLS SNI was accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(data, received) {
				t.Fatal("Relay corrupted payload")
			}
		})
	}
}

func fturnStartBox(t *testing.T, config map[string]any) {
	t.Helper()
	ctx, cancel := context.WithCancel(BaseContext(nil))
	data, err := json.Marshal(config)
	if err != nil {
		t.Fatal(err)
	}
	options, err := parseConfig(ctx, string(data))
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	instance, err := box.New(box.Options{Context: ctx, Options: options})
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	if err = instance.Start(); err != nil {
		instance.Close()
		cancel()
		t.Fatal(err)
	}
	t.Cleanup(func() { instance.Close(); cancel() })
}

func fturnRelay(t *testing.T, targetPort int) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	connections := make(map[net.Conn]bool)
	t.Cleanup(func() {
		listener.Close()
		mu.Lock()
		defer mu.Unlock()
		for c := range connections {
			c.Close()
		}
	})
	go func() {
		for {
			front, err := listener.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			connections[front] = true
			mu.Unlock()
			go func() {
				defer front.Close()
				back, err := net.DialTimeout("tcp4", net.JoinHostPort("127.0.0.1", fturnPort(targetPort)), 5*time.Second)
				if err != nil {
					return
				}
				mu.Lock()
				connections[back] = true
				mu.Unlock()
				defer back.Close()
				done := make(chan struct{})
				go func() { io.Copy(back, front); close(done) }()
				io.Copy(front, back)
				front.Close()
				back.Close()
				<-done
				mu.Lock()
				delete(connections, front)
				delete(connections, back)
				mu.Unlock()
			}()
		}
	}()
	return listener.Addr().(*net.TCPAddr).Port
}

func fturnPort(port int) string { return big.NewInt(int64(port)).String() }
func fturnFreePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	return port
}
func fturnTestCertificate(t *testing.T) (string, string) {
	t.Helper()
	private, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "front.example"}, DNSNames: []string{"front.example"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour), IsCA: true, BasicConstraintsValid: true,
		KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &private.PublicKey, private)
	if err != nil {
		t.Fatal(err)
	}
	key, err := x509.MarshalPKCS8PrivateKey(private)
	if err != nil {
		t.Fatal(err)
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})), string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: key}))
}
