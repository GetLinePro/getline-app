package config

import (
	"strings"
	"testing"

	"github.com/metacubex/mihomo/config"
)

func baseLocalProxyProfile() string {
	return `
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules:
  - MATCH,DIRECT
`
}

func TestLocalProxySessionOverrideCreatesListenerAndRule(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(baseLocalProxyProfile()))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	WriteOverride(OverrideSlotSession, `{
		"local-proxy": {
			"listen": "192.168.1.5",
			"port": 41235,
			"username": "getline",
			"password": "S3cure-Passw0rd-Value"
		}
	}`)
	defer ClearOverride(OverrideSlotSession)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	if len(raw.Listeners) != 1 {
		t.Fatalf("expected exactly one listener, got %d: %v", len(raw.Listeners), raw.Listeners)
	}
	l := raw.Listeners[0]
	if l["name"] != LocalProxyListenerName {
		t.Fatalf("wrong listener name: %v", l["name"])
	}
	if l["type"] != "mixed" {
		t.Fatalf("wrong listener type: %v", l["type"])
	}
	if l["listen"] != "192.168.1.5" {
		t.Fatalf("wrong listen address: %v", l["listen"])
	}
	if l["port"] != "41235" {
		t.Fatalf("wrong port: %v", l["port"])
	}
	if udp, ok := l["udp"].(bool); !ok || udp {
		t.Fatalf("udp must be false, got: %v", l["udp"])
	}
	users, ok := l["users"].([]map[string]any)
	if !ok || len(users) != 1 {
		t.Fatalf("expected exactly one user, got: %v", l["users"])
	}
	if users[0]["username"] != "getline" || users[0]["password"] != "S3cure-Passw0rd-Value" {
		t.Fatalf("wrong credentials: %v", users[0])
	}

	if len(raw.Rule) == 0 {
		t.Fatal("expected a prepended product rule")
	}
	prepended := raw.Rule[0]
	if !strings.HasPrefix(prepended, "AND,((IN-NAME,"+LocalProxyListenerName+"),") {
		t.Fatalf("prepended rule missing IN-NAME scope: %s", prepended)
	}
	if !strings.HasSuffix(prepended, ",REJECT") {
		t.Fatalf("prepended rule must reject: %s", prepended)
	}
	for _, cidr := range localProxyDestinationRejectCIDRs {
		if !strings.Contains(prepended, "(IP-CIDR,"+cidr+")") {
			t.Fatalf("prepended rule missing %s: %s", cidr, prepended)
		}
	}
	if strings.Contains(prepended, "no-resolve") {
		t.Fatalf("prepended rule must not use no-resolve: %s", prepended)
	}
	if raw.Rule[len(raw.Rule)-1] != "MATCH,DIRECT" {
		t.Fatalf("subscription rule must survive after the product rule: %v", raw.Rule)
	}
}

func TestLocalProxyAbsentOverrideCreatesNoListener(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(baseLocalProxyProfile()))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	if len(raw.Listeners) != 0 {
		t.Fatalf("expected no listener without an override, got: %v", raw.Listeners)
	}
	if raw.Rule[0] != "MATCH,DIRECT" {
		t.Fatalf("no product rule should be prepended, got: %v", raw.Rule)
	}
}

func TestLocalProxyPersistSlotIsIgnored(t *testing.T) {
	raw, err := config.UnmarshalRawConfig([]byte(baseLocalProxyProfile()))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	WriteOverride(OverrideSlotPersist, `{
		"local-proxy": {
			"listen": "192.168.1.5",
			"port": 41235,
			"username": "getline",
			"password": "S3cure-Passw0rd-Value"
		}
	}`)
	defer ClearOverride(OverrideSlotPersist)

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	if len(raw.Listeners) != 0 {
		t.Fatalf("persist slot must never create the local proxy listener, got: %v", raw.Listeners)
	}
}

func TestLocalProxySubscriptionCannotInjectOverride(t *testing.T) {
	// A malicious subscription cannot set the local-proxy key: that key is
	// only ever read from the Session override string, never from parsed
	// RawConfig/YAML fields, and RawConfig has no such field to begin with.
	raw, err := config.UnmarshalRawConfig([]byte(`
local-proxy:
  listen: 192.168.1.5
  port: 41235
  username: getline
  password: S3cure-Passw0rd-Value
proxies:
  - name: test
    type: socks5
    server: 127.0.0.1
    port: 1080
proxy-groups: []
rules:
  - MATCH,DIRECT
`))
	if err != nil {
		t.Fatalf("UnmarshalRawConfig: %v", err)
	}

	if err := process(raw, t.TempDir()); err != nil {
		t.Fatalf("process: %v", err)
	}

	if len(raw.Listeners) != 0 {
		t.Fatalf("subscription YAML must never create the local proxy listener, got: %v", raw.Listeners)
	}
}

func TestLocalProxyInvalidOverrideValuesFailClosed(t *testing.T) {
	cases := map[string]string{
		"non-ipv4 listen": `{"local-proxy":{"listen":"::1","port":41235,"username":"getline","password":"pw"}}`,
		"wildcard listen": `{"local-proxy":{"listen":"0.0.0.0","port":41235,"username":"getline","password":"pw"}}`,
		"low port":        `{"local-proxy":{"listen":"192.168.1.5","port":80,"username":"getline","password":"pw"}}`,
		"empty username":  `{"local-proxy":{"listen":"192.168.1.5","port":41235,"username":"","password":"pw"}}`,
		"colon username":  `{"local-proxy":{"listen":"192.168.1.5","port":41235,"username":"get:line","password":"pw"}}`,
		"empty password":  `{"local-proxy":{"listen":"192.168.1.5","port":41235,"username":"getline","password":""}}`,
		"whitespace pw":   `{"local-proxy":{"listen":"192.168.1.5","port":41235,"username":"getline","password":"has space"}}`,
	}

	for name, overrideJSON := range cases {
		t.Run(name, func(t *testing.T) {
			raw, err := config.UnmarshalRawConfig([]byte(baseLocalProxyProfile()))
			if err != nil {
				t.Fatalf("UnmarshalRawConfig: %v", err)
			}

			WriteOverride(OverrideSlotSession, overrideJSON)
			defer ClearOverride(OverrideSlotSession)

			if err := process(raw, t.TempDir()); err != nil {
				t.Fatalf("process: %v", err)
			}

			if len(raw.Listeners) != 0 {
				t.Fatalf("%s: expected fail-closed, got listener: %v", name, raw.Listeners)
			}
		})
	}
}

func TestLocalProxyPasswordMayContainColon(t *testing.T) {
	override := &LocalProxyOverride{
		Listen:   "192.168.1.5",
		Port:     41235,
		Username: "getline",
		Password: "part:part",
	}

	if err := validateLocalProxyOverride(override); err != nil {
		t.Fatalf("colon is valid after the HTTP Basic username delimiter: %v", err)
	}
}
