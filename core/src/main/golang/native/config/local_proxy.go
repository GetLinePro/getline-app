package config

import (
	"encoding/json"
	"errors"
	"net/netip"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/log"
)

// LocalProxyListenerName is the product-owned named mixed listener. Rule
// injection (patchLocalProxyRule) scopes to this exact name via IN-NAME, so
// it must stay in sync with the listener synthesized below.
const LocalProxyListenerName = "GETLINE-LAN-PROXY"

const (
	localProxyMinPort          = 1024
	localProxyMaxPort          = 65535
	localProxyMaxCredentialLen = 128
)

// localProxyDestinationRejectCIDRs are destinations the LAN proxy uniquely
// exposes beyond what a same-segment client already reaches directly — see
// docs/internal/plan-local-lan-proxy-2026-08-27.md Decisions for the
// AP-client-isolation and multi-homed-link-local reasoning. Multicast and
// other reserved ranges are intentionally excluded.
var localProxyDestinationRejectCIDRs = []string{
	"127.0.0.0/8",
	"10.0.0.0/8",
	"172.16.0.0/12",
	"192.168.0.0/16",
	"100.64.0.0/10",
	"169.254.0.0/16",
}

// LocalProxyOverride is the narrow typed shape of the Session slot's
// "local-proxy" key. It is decoded independently of the whole-RawConfig
// decode in patchOverride, so a session override can request GetLine's own
// listener without ever widening what subscription/Persist JSON can reach
// inside config.RawConfig.
type LocalProxyOverride struct {
	Listen   string `json:"listen"`
	Port     int    `json:"port"`
	Username string `json:"username"`
	Password string `json:"password"`
}

type localProxyOverrideEnvelope struct {
	LocalProxy *LocalProxyOverride `json:"local-proxy"`
}

// decodeLocalProxyOverride reads only the "local-proxy" key out of the
// Session override JSON. It never touches OverrideSlotPersist: callers pass
// ReadOverride(OverrideSlotSession) explicitly, so a legacy persisted
// override can carry this key and it is still ignored.
func decodeLocalProxyOverride(sessionJSON string) (*LocalProxyOverride, error) {
	var envelope localProxyOverrideEnvelope
	if err := json.NewDecoder(strings.NewReader(sessionJSON)).Decode(&envelope); err != nil {
		return nil, err
	}
	return envelope.LocalProxy, nil
}

// validateLocalProxyOverride fails closed on anything but an exact IPv4
// listen address, an unprivileged/user port, and non-empty safe-ASCII
// credentials. udp is not a decoded field at all — the synthesized listener
// below always sets udp:false, so there is no knob to validate.
func validateLocalProxyOverride(o *LocalProxyOverride) error {
	addr, err := netip.ParseAddr(o.Listen)
	if err != nil || !addr.Is4() {
		return errors.New("listen must be an exact IPv4 address")
	}
	if addr.IsUnspecified() || addr.IsLoopback() || addr.IsMulticast() {
		return errors.New("listen must not be a wildcard, loopback or multicast address")
	}
	if o.Port < localProxyMinPort || o.Port > localProxyMaxPort {
		return errors.New("port out of range")
	}
	if !isLocalProxySafeUsername(o.Username) {
		return errors.New("username invalid")
	}
	if !isLocalProxySafeASCII(o.Password) {
		return errors.New("password invalid")
	}
	return nil
}

// isLocalProxySafeUsername additionally excludes ':' because Mihomo's mixed
// listener parses HTTP Basic credentials at the first colon. SOCKS5 would
// accept such a username, but HTTP clients could never authenticate it.
func isLocalProxySafeUsername(s string) bool {
	return isLocalProxySafeASCII(s) && !strings.ContainsRune(s, ':')
}

// isLocalProxySafeASCII requires non-empty, bounded, printable/graphic ASCII
// (no whitespace or control bytes) so the value is safe to hand to Mihomo's
// auth store and to HTTP Basic / SOCKS5 username-password negotiation.
func isLocalProxySafeASCII(s string) bool {
	if s == "" || len(s) > localProxyMaxCredentialLen {
		return false
	}
	for i := 0; i < len(s); i++ {
		b := s[i]
		if b < 0x21 || b > 0x7E {
			return false
		}
	}
	return true
}

// validLocalProxyOverride centralizes decode+validate so both processors
// below (listener synthesis and rule injection) agree on activation without
// sharing mutable state across the processor chain. A missing or invalid
// override is not an error for the whole config load — it just means no
// listener and no product rule are added (fail closed).
func validLocalProxyOverride() *LocalProxyOverride {
	override, err := decodeLocalProxyOverride(ReadOverride(OverrideSlotSession))
	if err != nil || override == nil {
		return nil
	}
	if err := validateLocalProxyOverride(override); err != nil {
		log.Warnln("local-proxy override rejected: %s", err.Error())
		return nil
	}
	return override
}

// patchLocalProxyListener synthesizes exactly one named mixed listener from
// the typed Session override — never from subscription YAML or the untyped
// RawConfig decode in patchOverride, which patchInbound has already cleared.
// A missing/invalid override leaves cfg.Listeners untouched (empty).
func patchLocalProxyListener(cfg *config.RawConfig, _ string) error {
	override := validLocalProxyOverride()
	if override == nil {
		return nil
	}

	cfg.Listeners = append(cfg.Listeners, map[string]any{
		"name":   LocalProxyListenerName,
		"type":   "mixed",
		"listen": override.Listen,
		"port":   strconv.Itoa(override.Port),
		"udp":    false,
		"users": []map[string]any{
			{"username": override.Username, "password": override.Password},
		},
	})

	return nil
}

// patchLocalProxyRule prepends one product rule scoped to
// IN-NAME,GETLINE-LAN-PROXY rejecting loopback/RFC1918/CGNAT/link-local
// destinations. No no-resolve param: a hostname resolving into one of these
// ranges must also be rejected. Every later subscription rule/group is left
// untouched; this only ever prepends.
func patchLocalProxyRule(cfg *config.RawConfig, _ string) error {
	if validLocalProxyOverride() == nil {
		return nil
	}

	cidrRules := make([]string, 0, len(localProxyDestinationRejectCIDRs))
	for _, cidr := range localProxyDestinationRejectCIDRs {
		cidrRules = append(cidrRules, "(IP-CIDR,"+cidr+")")
	}

	rule := "AND,((IN-NAME," + LocalProxyListenerName + "),(OR,(" +
		strings.Join(cidrRules, ",") + "))),REJECT"

	cfg.Rule = append([]string{rule}, cfg.Rule...)

	return nil
}
