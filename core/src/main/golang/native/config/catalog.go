package config

import (
	"encoding/json"
	"os"
	P "path"
	"strings"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/log"
	T "github.com/metacubex/mihomo/tunnel"
)

const serverCatalogFile = "server-catalog.json"
const serverCatalogTmpFile = "server-catalog.json.tmp"

const serverCatalogVersion = 1

const globalGroupName = "GLOBAL"

// Placeholder member Mihomo leaves in a group whose provider is not loaded.
const compatibleProxyName = "COMPATIBLE"

// Offline inventory of proxy-groups after a successful validate/parse.
// Product UI reads this when Mihomo is not loaded. Same parser as import.
type serverCatalog struct {
	Version int                  `json:"version"`
	Mode    string               `json:"mode"`
	Groups  []serverCatalogGroup `json:"groups"`
}

type serverCatalogGroup struct {
	Name    string               `json:"name"`
	Type    string               `json:"type"`
	Now     string               `json:"now"`
	Hidden  bool                 `json:"hidden"`
	Proxies []serverCatalogProxy `json:"proxies"`
}

type serverCatalogProxy struct {
	Name  string `json:"name"`
	Type  string `json:"type"`
	Group bool   `json:"group"`
}

func proxyGroupNames(rawCfg *config.RawConfig) []string {
	names := make([]string, 0, len(rawCfg.ProxyGroup))
	for _, mapping := range rawCfg.ProxyGroup {
		name, _ := mapping["name"].(string)
		if name == "" {
			continue
		}
		names = append(names, name)
	}
	return names
}

func catalogGroupOrder(order []string, cfg *config.Config) []string {
	hasGlobal := false
	for _, name := range order {
		if name == globalGroupName {
			hasGlobal = true
			break
		}
	}
	if hasGlobal {
		return order
	}
	if _, ok := cfg.Proxies[globalGroupName]; !ok {
		return order
	}
	names := make([]string, 0, len(order)+1)
	names = append(names, globalGroupName)
	return append(names, order...)
}

// persistTunnelMode is YAML mode plus persist override. Session must not
// land in the catalog: it is cleared when the tunnel stops, while the
// catalog outlives that session.
func persistTunnelMode(profilePath string) string {
	data, err := os.ReadFile(P.Join(profilePath, "config.yaml"))
	if err != nil {
		return T.Rule.String()
	}
	return persistTunnelModeFrom(data, ReadOverride(OverrideSlotPersist))
}

func persistTunnelModeFrom(yaml []byte, persistJSON string) string {
	raw, err := config.UnmarshalRawConfig(yaml)
	if err != nil {
		return T.Rule.String()
	}
	if persistJSON != "" {
		if err := json.NewDecoder(strings.NewReader(persistJSON)).Decode(raw); err != nil {
			// Same as patchOverride: keep YAML mode if persist JSON is broken.
		}
	}
	mode := raw.Mode.String()
	if mode == "" || mode == "Unknown" {
		return T.Rule.String()
	}
	return mode
}

func writeServerCatalogBestEffort(path string, mode string, order []string, cfg *config.Config) {
	if err := writeServerCatalog(path, mode, order, cfg); err != nil {
		log.Warnln("write server catalog: %s", err.Error())
	}
}

// Read-only by construction: whatever Parse already resolved, nothing else.
// Provider contents are deliberately not loaded here — provider.Initial()
// starts health checks and refresh loops, which an import must not do.
// A group fed only by `use:` therefore lands empty; the offline list shows
// Empty for it, and the live path (loaded runtime) is unaffected.
func writeServerCatalog(path string, mode string, order []string, cfg *config.Config) error {
	names := catalogGroupOrder(order, cfg)
	catalog := serverCatalog{
		Version: serverCatalogVersion,
		Mode:    mode,
		Groups:  make([]serverCatalogGroup, 0, len(names)),
	}

	for _, name := range names {
		proxy, ok := cfg.Proxies[name]
		if !ok {
			continue
		}
		group, ok := proxy.Adapter().(outboundgroup.ProxyGroup)
		if !ok {
			continue
		}

		members := group.Proxies()
		proxies := make([]serverCatalogProxy, 0, len(members))
		for _, member := range members {
			// COMPATIBLE is the placeholder an unloaded provider leaves behind.
			// Writing it would offer the UI a node that cannot be selected.
			if member.Type() == C.Compatible {
				continue
			}
			_, isGroup := member.Adapter().(outboundgroup.ProxyGroup)
			proxies = append(proxies, serverCatalogProxy{
				Name:  member.Name(),
				Type:  member.Type().String(),
				Group: isGroup,
			})
		}

		// Same reason as the member filter: a group left with the placeholder
		// must not report it as the current selection.
		now := group.Now()
		if now == compatibleProxyName {
			now = ""
		}

		catalog.Groups = append(catalog.Groups, serverCatalogGroup{
			Name:    name,
			Type:    group.Type().String(),
			Now:     now,
			Hidden:  group.Hidden(),
			Proxies: proxies,
		})
	}

	data, err := json.Marshal(catalog)
	if err != nil {
		return err
	}

	dest := P.Join(path, serverCatalogFile)
	tmp := P.Join(path, serverCatalogTmpFile)
	defer os.Remove(tmp)
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return err
	}
	return nil
}
