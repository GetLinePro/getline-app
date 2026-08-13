package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
)

const catalogSampleYAML = `
proxies:
  - name: leaf-a
    type: socks5
    server: 127.0.0.1
    port: 1080
  - name: leaf-b
    type: socks5
    server: 127.0.0.1
    port: 1081
proxy-groups:
  - name: AUTO
    type: url-test
    url: https://www.gstatic.com/generate_204
    interval: 300
    proxies:
      - leaf-a
      - leaf-b
  - name: VPN
    type: select
    proxies:
      - AUTO
      - leaf-a
      - leaf-b
  - name: hidden-tech
    type: select
    hidden: true
    proxies:
      - leaf-a
rules: []
`

func TestValidateAndPrepareWritesServerCatalog(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(catalogSampleYAML), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	raw, err := os.ReadFile(filepath.Join(profile, serverCatalogFile))
	if err != nil {
		t.Fatalf("read catalog: %v", err)
	}

	var catalog serverCatalog
	if err := json.Unmarshal(raw, &catalog); err != nil {
		t.Fatalf("decode catalog: %v", err)
	}
	if catalog.Version != serverCatalogVersion {
		t.Fatalf("version = %d", catalog.Version)
	}
	if catalog.Mode != "rule" {
		t.Fatalf("mode = %q", catalog.Mode)
	}
	if got := groupNames(catalog); len(got) < 4 || got[0] != "GLOBAL" || got[1] != "AUTO" || got[2] != "VPN" || got[3] != "hidden-tech" {
		t.Fatalf("group order = %v", got)
	}
	if catalog.Groups[0].Name != "GLOBAL" || catalog.Groups[0].Type != "Selector" {
		t.Fatalf("GLOBAL group = %+v", catalog.Groups[0])
	}

	auto := catalog.Groups[1]
	if auto.Name != "AUTO" || auto.Type != "URLTest" || auto.Hidden {
		t.Fatalf("AUTO group = %+v", auto)
	}
	if len(auto.Proxies) != 2 || auto.Proxies[0].Name != "leaf-a" || auto.Proxies[0].Group {
		t.Fatalf("AUTO proxies = %+v", auto.Proxies)
	}

	vpn := catalog.Groups[2]
	if vpn.Name != "VPN" || vpn.Type != "Selector" || vpn.Hidden {
		t.Fatalf("VPN group = %+v", vpn)
	}
	if len(vpn.Proxies) != 3 {
		t.Fatalf("VPN proxies = %+v", vpn.Proxies)
	}
	if !vpn.Proxies[0].Group || vpn.Proxies[0].Name != "AUTO" || vpn.Proxies[0].Type != "URLTest" {
		t.Fatalf("VPN first proxy = %+v", vpn.Proxies[0])
	}
	if vpn.Now == "" {
		t.Fatal("VPN now is empty")
	}

	hidden := catalog.Groups[3]
	if hidden.Name != "hidden-tech" || !hidden.Hidden || hidden.Type != "Selector" {
		t.Fatalf("hidden group = %+v", hidden)
	}
}

func TestValidateAndPrepareWritesGlobalMode(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	body := "mode: global\n" + catalogSampleYAML
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	catalog := readCatalog(t, profile)
	if catalog.Mode != "global" {
		t.Fatalf("mode = %q", catalog.Mode)
	}
	if catalog.Groups[0].Name != "GLOBAL" {
		t.Fatalf("first group = %s", catalog.Groups[0].Name)
	}
}

func TestValidateAndPrepareWritesDirectMode(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	body := "mode: direct\n" + catalogSampleYAML
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	catalog := readCatalog(t, profile)
	if catalog.Mode != "direct" {
		t.Fatalf("mode = %q", catalog.Mode)
	}
}

func TestCatalogModeIgnoresSessionOverride(t *testing.T) {
	WriteOverride(OverrideSlotSession, `{"mode":"direct"}`)
	defer ClearOverride(OverrideSlotSession)

	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(catalogSampleYAML), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	catalog := readCatalog(t, profile)
	if catalog.Mode != "rule" {
		t.Fatalf("mode = %q, want rule (session direct must not persist)", catalog.Mode)
	}
}

func TestPersistTunnelModeUsesPersistNotSession(t *testing.T) {
	yaml := []byte("mode: rule\n" + catalogSampleYAML)
	if got := persistTunnelModeFrom(yaml, `{"mode":"global"}`); got != "global" {
		t.Fatalf("persist override = %q, want global", got)
	}
	if got := persistTunnelModeFrom(yaml, `{}`); got != "rule" {
		t.Fatalf("empty persist = %q, want rule", got)
	}
	if got := persistTunnelModeFrom([]byte("mode: global\n"+catalogSampleYAML), `{}`); got != "global" {
		t.Fatalf("yaml global = %q", got)
	}
}

func TestWriteServerCatalogRemovesTmpOnWriteFileError(t *testing.T) {
	dir := t.TempDir()
	tmp := filepath.Join(dir, serverCatalogTmpFile)
	if err := os.Mkdir(tmp, 0700); err != nil {
		t.Fatal(err)
	}

	err := writeServerCatalog(dir, "rule", nil, &config.Config{})
	if err == nil {
		t.Fatal("expected write failure against tmp directory")
	}
	if _, statErr := os.Stat(tmp); !os.IsNotExist(statErr) {
		t.Fatalf("tmp left behind after WriteFile error: %v", statErr)
	}
}

func TestValidateAndPrepareSucceedsWhenCatalogUnwritable(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(catalogSampleYAML), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(filepath.Join(profile, serverCatalogFile), 0700); err != nil {
		t.Fatal(err)
	}

	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("catalog write must not fail import: %v", err)
	}
	info, err := os.Stat(filepath.Join(profile, serverCatalogFile))
	if err != nil || !info.IsDir() {
		t.Fatalf("destination should stay the blocking directory: %v", err)
	}
	if _, statErr := os.Stat(filepath.Join(profile, serverCatalogTmpFile)); !os.IsNotExist(statErr) {
		t.Fatalf("tmp catalog left behind: %v", statErr)
	}
}

// Provider contents are not loaded while building the catalog: provider.Initial()
// starts health checks and refresh loops. A `use:`-only group is therefore written
// empty rather than carrying the COMPATIBLE placeholder.
func TestValidateAndPrepareLeavesProxyProviderUseEmpty(t *testing.T) {
	root := t.TempDir()
	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(filepath.Join(profile, "providers"), 0700); err != nil {
		t.Fatal(err)
	}
	providerBody := `
proxies:
  - name: prov-a
    type: socks5
    server: 127.0.0.1
    port: 1080
  - name: prov-b
    type: socks5
    server: 127.0.0.1
    port: 1081
`
	if err := os.WriteFile(filepath.Join(profile, "providers", "local.yaml"), []byte(providerBody), 0600); err != nil {
		t.Fatal(err)
	}

	localFile := filepath.Join(root, "download.yaml")
	body := `
proxy-providers:
  local:
    type: file
    path: local.yaml
proxy-groups:
  - name: VPN
    type: select
    use:
      - local
rules: []
`
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}

	home := C.Path.HomeDir()
	C.SetHomeDir(profile)
	t.Cleanup(func() { C.SetHomeDir(home) })

	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	catalog := readCatalog(t, profile)
	var vpn *serverCatalogGroup
	for i := range catalog.Groups {
		if catalog.Groups[i].Name == "VPN" {
			vpn = &catalog.Groups[i]
			break
		}
	}
	if vpn == nil {
		t.Fatalf("VPN group missing: %v", groupNames(catalog))
	}
	if got := proxyNames(*vpn); len(got) != 0 {
		t.Fatalf("VPN proxies = %v, want none (provider not loaded)", got)
	}
	if vpn.Now != "" {
		t.Fatalf("VPN now = %q, want empty (placeholder must not be a selection)", vpn.Now)
	}
}

// The placeholder must not reach the catalog even when it is the only member.
func TestWriteServerCatalogSkipsCompatiblePlaceholder(t *testing.T) {
	root := t.TempDir()
	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(filepath.Join(profile, "providers"), 0700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(profile, "providers", "local.yaml"), []byte("proxies: []\n"), 0600); err != nil {
		t.Fatal(err)
	}

	localFile := filepath.Join(root, "download.yaml")
	body := `
proxy-providers:
  local:
    type: file
    path: local.yaml
proxy-groups:
  - name: VPN
    type: select
    use:
      - local
rules: []
`
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}

	home := C.Path.HomeDir()
	C.SetHomeDir(profile)
	t.Cleanup(func() { C.SetHomeDir(home) })

	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	for _, group := range readCatalog(t, profile).Groups {
		for _, proxy := range group.Proxies {
			if proxy.Name == compatibleProxyName {
				t.Fatalf("group %q carries the %s placeholder", group.Name, compatibleProxyName)
			}
		}
		if group.Now == compatibleProxyName {
			t.Fatalf("group %q selects the %s placeholder", group.Name, compatibleProxyName)
		}
	}
}

func proxyNames(group serverCatalogGroup) []string {
	names := make([]string, 0, len(group.Proxies))
	for _, proxy := range group.Proxies {
		names = append(names, proxy.Name)
	}
	return names
}

func groupNames(catalog serverCatalog) []string {
	names := make([]string, 0, len(catalog.Groups))
	for _, group := range catalog.Groups {
		names = append(names, group.Name)
	}
	return names
}

func readCatalog(t *testing.T, profile string) serverCatalog {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(profile, serverCatalogFile))
	if err != nil {
		t.Fatalf("read catalog: %v", err)
	}
	var catalog serverCatalog
	if err := json.Unmarshal(raw, &catalog); err != nil {
		t.Fatalf("decode catalog: %v", err)
	}
	return catalog
}

func TestValidateAndPrepareDoesNotWriteCatalogOnInvalidYaml(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "broken.yaml")
	if err := os.WriteFile(localFile, []byte("proxies: [\n"), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {})
	if err == nil {
		t.Fatal("invalid YAML unexpectedly accepted")
	}
	if _, statErr := os.Stat(filepath.Join(profile, serverCatalogFile)); !os.IsNotExist(statErr) {
		t.Fatalf("catalog exists after failed prepare: %v", statErr)
	}
}
