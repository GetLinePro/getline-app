package config

import (
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/common/observable"
	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

const inboundSignalPrefix = "subscription attempted fields:"

func TestPatchInboundNeutralizesHostileInboundAndSignals(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(`
mixed-port: 7890
port: 8080
socks-port: 1080
allow-lan: true
bind-address: "0.0.0.0"
lan-allowed-ips:
  - 10.0.0.0/8
lan-disallowed-ips:
  - 192.168.0.0/16
skip-auth-prefixes:
  - 127.0.0.1/32
inbound-tfo: true
inbound-mptcp: true
ss-config: "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ@127.0.0.1:8388"
vmess-config: "vmess://example"
tuic-server:
  enable: true
  listen: 0.0.0.0:10443
  certificate: /tmp/cert.pem
  private-key: /tmp/key.pem
authentication:
  - "user:secret-should-not-appear-in-logs"
external-controller: 127.0.0.1:9090
external-controller-tls: 127.0.0.1:9443
external-controller-unix: /data/local/tmp/clash.sock
external-controller-pipe: \\\\.\\pipe\\clash
external-doh-server: 127.0.0.1:443
secret: "controller-secret-must-not-log"
listeners:
  - name: evil-mixed
    type: mixed
    port: 9999
tunnels:
  - network: [tcp]
    address: 127.0.0.1:18080
    target: 1.1.1.1:443
dns:
  enable: true
  listen: 0.0.0.0:53
  listen-routing-mark: 42
  nameserver:
    - 1.1.1.1
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules: []
`))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	sub := log.Subscribe()
	defer log.UnSubscribe(sub)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	assertInboundOwned(t, raw)

	payloads := collectLogPayloads(t, sub, 50*time.Millisecond)
	joined := strings.Join(payloads, "\n")
	signalLine := findSignalLine(payloads)
	if signalLine == "" {
		t.Fatalf("missing batch signal line in logs:\n%s", joined)
	}
	assertSignalFields(t, signalLine,
		"port", "socks-port", "mixed-port", "allow-lan", "bind-address",
		"lan-allowed-ips", "lan-disallowed-ips", "skip-auth-prefixes",
		"inbound-tfo", "inbound-mptcp",
		"ss-config", "vmess-config", "tuic-server",
		"authentication", "listeners", "tunnels",
		"dns.listen", "dns.listen-routing-mark",
		"external-controller", "external-controller-tls",
		"external-controller-unix", "external-controller-pipe",
		"external-doh-server", "secret",
	)
	if strings.Contains(joined, "secret-should-not-appear-in-logs") ||
		strings.Contains(joined, "controller-secret-must-not-log") {
		t.Error("log leaked secret value")
	}
	if strings.Contains(joined, "ss://") || strings.Contains(joined, "vmess://") {
		t.Error("log leaked ss/vmess config value")
	}
}

func TestPatchInboundCleanProfileIsSilent(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(`
mode: rule
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules: []
`))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	sub := log.Subscribe()
	defer log.UnSubscribe(sub)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	assertInboundOwned(t, raw)

	payloads := collectLogPayloads(t, sub, 30*time.Millisecond)
	for _, p := range payloads {
		if strings.Contains(p, inboundSignalPrefix) {
			t.Fatalf("clean profile must not signal, got: %s", p)
		}
	}
}

func TestDetectInboundIgnoresOverrideOnlyInbound(t *testing.T) {
	// Clean subscription: no inbound. Override injects inbound after detect.
	// Signal must stay silent — origin is app override, not subscription.
	raw, err := config.UnmarshalRawConfig([]byte(`
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules: []
`))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	WriteOverride(OverrideSlotSession, `{
		"allow-lan":true,
		"mixed-port":7890,
		"ss-config":"ss://override",
		"vmess-config":"vmess://override",
		"tuic-server":{"enable":true,"listen":"0.0.0.0:1"},
		"external-controller":"127.0.0.1:9090",
		"secret":"from-override",
		"listeners":[{"name":"x","type":"http","port":1}]
	}`)
	defer ClearOverride(OverrideSlotSession)

	sub := log.Subscribe()
	defer log.UnSubscribe(sub)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	assertInboundOwned(t, raw)

	payloads := collectLogPayloads(t, sub, 30*time.Millisecond)
	for _, p := range payloads {
		if strings.Contains(p, inboundSignalPrefix) {
			t.Fatalf("override-only inbound must not signal subscription attempt, got: %s", p)
		}
	}
}

func TestDetectInboundSignalsSubscriptionEvenIfOverrideAlsoSets(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(`
allow-lan: true
mixed-port: 7890
ss-config: "ss://from-sub"
external-controller-unix: /tmp/from-sub.sock
listeners:
  - name: from-sub
    type: mixed
    port: 9999
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules: []
`))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	WriteOverride(OverrideSlotSession, `{"allow-lan":false,"mixed-port":0,"ss-config":""}`)
	defer ClearOverride(OverrideSlotSession)

	sub := log.Subscribe()
	defer log.UnSubscribe(sub)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	assertInboundOwned(t, raw)

	payloads := collectLogPayloads(t, sub, 30*time.Millisecond)
	signalLine := findSignalLine(payloads)
	if signalLine == "" {
		t.Fatalf("missing batch signal line in logs:\n%s", strings.Join(payloads, "\n"))
	}
	assertSignalFields(t, signalLine,
		"allow-lan", "mixed-port", "ss-config", "listeners", "external-controller-unix",
	)
}

func assertInboundOwned(t *testing.T, raw *config.RawConfig) {
	t.Helper()
	if raw.Port != 0 || raw.SocksPort != 0 || raw.RedirPort != 0 || raw.TProxyPort != 0 || raw.MixedPort != 0 {
		t.Fatalf("ports not owned: port=%d socks=%d redir=%d tproxy=%d mixed=%d",
			raw.Port, raw.SocksPort, raw.RedirPort, raw.TProxyPort, raw.MixedPort)
	}
	if raw.ShadowSocksConfig != "" || raw.VmessConfig != "" {
		t.Fatalf("ss/vmess config not cleared: ss=%q vmess=%q", raw.ShadowSocksConfig, raw.VmessConfig)
	}
	if tuicServerPresent(raw.TuicServer) {
		t.Fatalf("tuic-server not cleared: enable=%v listen=%q", raw.TuicServer.Enable, raw.TuicServer.Listen)
	}
	if raw.AllowLan {
		t.Fatal("allow-lan still true")
	}
	if raw.BindAddress != "*" {
		t.Fatalf("bind-address not reset: %q", raw.BindAddress)
	}
	// Empty LanAllowedIPs is deny-all in mihomo and breaks system-proxy (127.x).
	if !isDefaultLanAllowedIPs(raw.LanAllowedIPs) {
		t.Fatalf("lan-allowed-ips must stay default allow-all, got %v", raw.LanAllowedIPs)
	}
	if len(raw.LanDisAllowedIPs) != 0 {
		t.Fatalf("lan-disallowed-ips not cleared: %v", raw.LanDisAllowedIPs)
	}
	if len(raw.SkipAuthPrefixes) != 0 {
		t.Fatalf("skip-auth-prefixes not cleared: %v", raw.SkipAuthPrefixes)
	}
	if raw.InboundTfo || raw.InboundMPTCP {
		t.Fatalf("inbound flags still set: tfo=%v mptcp=%v", raw.InboundTfo, raw.InboundMPTCP)
	}
	if len(raw.Authentication) != 0 {
		t.Fatalf("authentication not cleared: %d entries", len(raw.Authentication))
	}
	if len(raw.Listeners) != 0 {
		t.Fatalf("listeners not cleared: %d", len(raw.Listeners))
	}
	if len(raw.Tunnels) != 0 {
		t.Fatalf("tunnels not cleared: %d", len(raw.Tunnels))
	}
	if raw.DNS.Listen != "" || raw.DNS.ListenRoutingMark != 0 {
		t.Fatalf("dns listen not cleared: listen=%q mark=%d", raw.DNS.Listen, raw.DNS.ListenRoutingMark)
	}
	if raw.ExternalController != "" || raw.ExternalControllerTLS != "" ||
		raw.ExternalControllerUnix != "" || raw.ExternalControllerPipe != "" ||
		raw.ExternalDohServer != "" || raw.Secret != "" ||
		raw.ExternalUI != "" || raw.ExternalUIURL != "" || raw.ExternalUIName != "" ||
		raw.ExternalControllerRoutingMark != 0 {
		t.Fatalf("external controller surface not cleared: ec=%q tls=%q unix=%q pipe=%q doh=%q secret-set=%v ui=%q",
			raw.ExternalController, raw.ExternalControllerTLS,
			raw.ExternalControllerUnix, raw.ExternalControllerPipe,
			raw.ExternalDohServer, raw.Secret != "", raw.ExternalUI)
	}
}

func findSignalLine(payloads []string) string {
	for _, p := range payloads {
		if strings.HasPrefix(p, inboundSignalPrefix) {
			return p
		}
	}
	return ""
}

// assertSignalFields requires exact comma-separated tokens after the prefix.
// Substring checks are wrong here: "port" ⊂ "socks-port",
// "external-controller" ⊂ "external-controller-tls", etc.
func assertSignalFields(t *testing.T, signalLine string, want ...string) {
	t.Helper()
	rest := strings.TrimSpace(strings.TrimPrefix(signalLine, inboundSignalPrefix))
	got := make(map[string]bool)
	if rest != "" {
		for _, f := range strings.Split(rest, ",") {
			got[strings.TrimSpace(f)] = true
		}
	}
	for _, field := range want {
		if !got[field] {
			t.Errorf("missing signal for %s in: %s", field, signalLine)
		}
	}
}

// collectLogPayloads drains subscription events until idle for idleWait or
// until maxWait elapses, whichever comes first after at least one idle period.
func collectLogPayloads(t *testing.T, sub observable.Subscription[log.Event], idleWait time.Duration) []string {
	t.Helper()
	maxWait := idleWait * 4
	if maxWait < 50*time.Millisecond {
		maxWait = 50 * time.Millisecond
	}
	deadline := time.After(maxWait)
	idle := time.NewTimer(idleWait)
	defer idle.Stop()

	var out []string
	for {
		select {
		case ev, ok := <-sub:
			if !ok {
				return out
			}
			out = append(out, ev.Payload)
			if !idle.Stop() {
				select {
				case <-idle.C:
				default:
				}
			}
			idle.Reset(idleWait)
		case <-idle.C:
			return out
		case <-deadline:
			return out
		}
	}
}
