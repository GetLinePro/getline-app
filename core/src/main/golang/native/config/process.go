package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"strings"

	"github.com/dlclark/regexp2"

	"cfa/native/common"

	"github.com/metacubex/mihomo/common/utils"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
)

var processors = []processor{
	// Signal while RawConfig is still pure subscription YAML — before override
	// merges app state and origin is lost.
	detectInbound,
	patchOverride,
	// After override: app security policy wins over both subscription and the
	// untyped override slot (which can re-inject inbound / controller into
	// full RawConfig).
	patchExternalController,
	patchInbound,
	patchGeneral,
	patchProfile,
	patchDns,
	patchTun,
	patchListeners,
	patchProviders,
	validConfig,
}

type processor func(cfg *config.RawConfig, profileDir string) error

func patchOverride(cfg *config.RawConfig, _ string) error {
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotPersist))).Decode(cfg); err != nil {
		log.Warnln("Apply persist override: %s", err.Error())
	}
	if err := json.NewDecoder(strings.NewReader(ReadOverride(OverrideSlotSession))).Decode(cfg); err != nil {
		log.Warnln("Apply session override: %s", err.Error())
	}

	return nil
}

// patchExternalController owns the control API surface. Clears every transport,
// secret, and residual controller metadata (UI path/url/name, cors,
// routing-mark) so subscription/override cannot open or decorate a local
// management server. Transports gate applyRoute; the extras are inert without
// an address but still app-owned for a consistent surface.
func patchExternalController(cfg *config.RawConfig, _ string) error {
	cfg.ExternalController = ""
	cfg.ExternalControllerTLS = ""
	cfg.ExternalControllerUnix = ""
	cfg.ExternalControllerPipe = ""
	cfg.ExternalDohServer = ""
	cfg.Secret = ""
	cfg.ExternalUI = ""
	cfg.ExternalUIURL = ""
	cfg.ExternalUIName = ""
	cfg.ExternalControllerRoutingMark = 0
	cfg.ExternalControllerCors = config.RawCors{}

	return nil
}

func patchGeneral(cfg *config.RawConfig, _ string) error {
	cfg.Interface = ""
	cfg.RoutingMark = 0
	// ExternalUI used to be set here when a controller address was present.
	// Controllers are hard-cleared by patchExternalController immediately above.

	// tls.custom-certifactes seeds the core's global CA pool: applying a config
	// resets the pool and adds this field's certificates (hub/executor). The
	// pool then backs every TLS the core speaks — rule/proxy provider and
	// geodata fetches (component/http), DoT/DoQ resolvers (dns/dot.go,
	// dns/doq.go), and the TLS leg of outbound adapters.
	//
	// The primary profile YAML is not in that set: since #75 an https:// Url
	// profile is fetched by the platform downloader on the Kotlin side
	// (ProfileProcessor.shouldUsePlatformPrimaryConfigTransport), and only
	// http:// profiles still take the core's own fetch path.
	//
	// Ordering favours the attacker rather than us: the pool is rebuilt on
	// apply, so a CA does not affect the fetch that carried it, but everything
	// after. Applied once, a profile could intercept its own provider updates.
	//
	// The app has no private-CA scenario and the override slot does not carry
	// this field. The rest of the tls: section only feeds the external
	// controller's own listener, which patchExternalController disabled above.
	cfg.TLS.CustomTrustCert = nil

	return nil
}

func patchProfile(cfg *config.RawConfig, _ string) error {
	cfg.Profile.StoreSelected = false
	cfg.Profile.StoreFakeIP = true

	return nil
}

func patchDns(cfg *config.RawConfig, _ string) error {
	if !cfg.DNS.Enable {
		cfg.DNS = config.DefaultRawConfig().DNS
		cfg.DNS.Enable = true
		cfg.DNS.NameServer = defaultNameServers
		cfg.DNS.EnhancedMode = C.DNSFakeIP
		cfg.DNS.FakeIPRange = defaultFakeIPRange
		cfg.DNS.FakeIPFilter = defaultFakeIPFilter

		cfg.ClashForAndroid.AppendSystemDNS = true
	}

	if cfg.ClashForAndroid.AppendSystemDNS {
		cfg.DNS.NameServer = append(cfg.DNS.NameServer, "system://")
	}

	return nil
}

func patchTun(cfg *config.RawConfig, _ string) error {
	cfg.Tun.Enable = false
	cfg.Tun.AutoRoute = false
	cfg.Tun.AutoDetectInterface = false
	return nil
}

// detectInbound logs inbound/control fields present in the subscription YAML.
// Must run before patchOverride: after override, app-owned values are mixed in
// and field origin is unrecoverable. Names only — never values (auth secrets).
func detectInbound(cfg *config.RawConfig, _ string) error {
	names := inboundFieldsPresent(cfg)
	if len(names) == 0 {
		return nil
	}
	log.Warnln("subscription attempted fields: %s", strings.Join(names, ", "))
	return nil
}

// patchInbound owns the inbound surface after override. Subscription and the
// untyped override slot must not open local listeners (classic ports, ss/vmess
// URI inbounds, tuic-server, listeners:, tunnels:, dns.listen) or LAN access.
//
// Safe defaults match a phone VPN path: no local proxy ports, allow-lan off.
// LanAllowedIPs keeps DefaultRawConfig's allow-all list: mihomo treats an empty
// list as deny-all, which would break the app's own 127.x system-proxy listener
// (VpnService HTTP proxy on Android 10+). LAN exposure is gated by AllowLan=false
// and zeroed public ports, not by this list.
//
// Future product needs (e.g. TV LAN share) must set these consciously here or
// via a typed AppOverride (#24), not from subscription YAML.
func patchInbound(cfg *config.RawConfig, _ string) error {
	cfg.Port = 0
	cfg.SocksPort = 0
	cfg.RedirPort = 0
	cfg.TProxyPort = 0
	cfg.MixedPort = 0
	cfg.ShadowSocksConfig = ""
	cfg.VmessConfig = ""
	cfg.TuicServer = config.RawTuicServer{}
	cfg.InboundTfo = false
	cfg.InboundMPTCP = false
	cfg.AllowLan = false
	cfg.BindAddress = "*"
	cfg.LanAllowedIPs = config.DefaultRawConfig().LanAllowedIPs
	cfg.LanDisAllowedIPs = nil
	cfg.SkipAuthPrefixes = nil
	cfg.Authentication = nil
	cfg.Listeners = nil
	cfg.Tunnels = nil
	cfg.DNS.Listen = ""
	cfg.DNS.ListenRoutingMark = 0

	return nil
}

// inboundFieldsPresent returns YAML-ish names of inbound/control fields that
// look set. Defaults from DefaultRawConfig (allow-lan:false, bind-address:"*",
// empty ports/listeners, tuic disabled) do not count — clean profiles silent.
func inboundFieldsPresent(cfg *config.RawConfig) []string {
	var names []string
	if cfg.Port != 0 {
		names = append(names, "port")
	}
	if cfg.SocksPort != 0 {
		names = append(names, "socks-port")
	}
	if cfg.RedirPort != 0 {
		names = append(names, "redir-port")
	}
	if cfg.TProxyPort != 0 {
		names = append(names, "tproxy-port")
	}
	if cfg.MixedPort != 0 {
		names = append(names, "mixed-port")
	}
	if cfg.ShadowSocksConfig != "" {
		names = append(names, "ss-config")
	}
	if cfg.VmessConfig != "" {
		names = append(names, "vmess-config")
	}
	if tuicServerPresent(cfg.TuicServer) {
		names = append(names, "tuic-server")
	}
	if cfg.InboundTfo {
		names = append(names, "inbound-tfo")
	}
	if cfg.InboundMPTCP {
		names = append(names, "inbound-mptcp")
	}
	if cfg.AllowLan {
		names = append(names, "allow-lan")
	}
	if cfg.BindAddress != "" && cfg.BindAddress != "*" {
		names = append(names, "bind-address")
	}
	if len(cfg.LanAllowedIPs) > 0 {
		// DefaultRawConfig seeds 0.0.0.0/0 + ::/0; only signal non-default sets.
		if !isDefaultLanAllowedIPs(cfg.LanAllowedIPs) {
			names = append(names, "lan-allowed-ips")
		}
	}
	if len(cfg.LanDisAllowedIPs) > 0 {
		names = append(names, "lan-disallowed-ips")
	}
	if len(cfg.SkipAuthPrefixes) > 0 {
		names = append(names, "skip-auth-prefixes")
	}
	if len(cfg.Authentication) > 0 {
		names = append(names, "authentication")
	}
	if len(cfg.Listeners) > 0 {
		names = append(names, "listeners")
	}
	if len(cfg.Tunnels) > 0 {
		names = append(names, "tunnels")
	}
	if cfg.DNS.Listen != "" {
		names = append(names, "dns.listen")
	}
	if cfg.DNS.ListenRoutingMark != 0 {
		names = append(names, "dns.listen-routing-mark")
	}
	if cfg.ExternalController != "" {
		names = append(names, "external-controller")
	}
	if cfg.ExternalControllerTLS != "" {
		names = append(names, "external-controller-tls")
	}
	if cfg.ExternalControllerUnix != "" {
		names = append(names, "external-controller-unix")
	}
	if cfg.ExternalControllerPipe != "" {
		names = append(names, "external-controller-pipe")
	}
	if cfg.ExternalDohServer != "" {
		names = append(names, "external-doh-server")
	}
	if cfg.Secret != "" {
		names = append(names, "secret")
	}
	return names
}

func tuicServerPresent(t config.RawTuicServer) bool {
	return t.Enable ||
		t.Listen != "" ||
		t.Certificate != "" ||
		t.PrivateKey != "" ||
		len(t.Token) > 0 ||
		len(t.Users) > 0
}

func isDefaultLanAllowedIPs(ips []netip.Prefix) bool {
	if len(ips) != 2 {
		return false
	}
	a := netip.MustParsePrefix("0.0.0.0/0")
	b := netip.MustParsePrefix("::/0")
	return (ips[0] == a && ips[1] == b) || (ips[0] == b && ips[1] == a)
}

// patchListeners drops tproxy/redir/tun listener types. Redundant after
// patchInbound (Listeners is already nil); kept as upstream CMFA behaviour.
func patchListeners(cfg *config.RawConfig, _ string) error {
	newListeners := make([]map[string]any, 0, len(cfg.Listeners))
	for _, mapping := range cfg.Listeners {
		if proxyType, existType := mapping["type"].(string); existType {
			switch proxyType {
			case "tproxy", "redir", "tun":
				continue // remove those listeners which is not supported
			}
		}
		newListeners = append(newListeners, mapping)
	}
	cfg.Listeners = newListeners
	return nil
}

func patchProviders(cfg *config.RawConfig, profileDir string) error {
	forEachProviders(cfg, func(index int, total int, key string, provider map[string]any, prefix string) {
		path, _ := provider["path"].(string)
		if len(path) > 0 {
			path = common.ResolveAsRoot(path)
		} else if url, ok := provider["url"].(string); ok {
			path = prefix + "/" + utils.MakeHash([]byte(url)).String() // same as C.GetPathByHash
		} else {
			return // both path and url are empty, maybe inline provider
		}
		provider["path"] = profileDir + "/providers/" + path
	})

	return nil
}

func validConfig(cfg *config.RawConfig, _ string) error {
	if len(cfg.Proxy) == 0 && len(cfg.ProxyProvider) == 0 {
		return errors.New("profile does not contain `proxies` or `proxy-providers`")
	}

	if _, err := regexp2.Compile(cfg.ClashForAndroid.UiSubtitlePattern, 0); err != nil {
		return fmt.Errorf("compile ui-subtitle-pattern: %s", err.Error())
	}

	return nil
}

func process(cfg *config.RawConfig, profileDir string) error {
	for _, p := range processors {
		if err := p(cfg, profileDir); err != nil {
			return err
		}
	}

	return nil
}
