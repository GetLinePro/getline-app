package config

import (
	"encoding/json"
	"fmt"
	"os"
	P "path"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
	"github.com/metacubex/mihomo/component/age"
)

const androidPolicyFile = "android-policy.json"
const androidPolicyTmpFile = "android-policy.json.tmp"
const androidPolicyVersion = 1

// Versioned security sidecar written next to config.yaml after a successful
// validate/parse. Unlike server-catalog.json this is fail-closed: a write
// failure rejects preparation.
type androidPolicy struct {
	Version          int      `json:"version"`
	ExcludedPackages []string `json:"excludedPackages"`
}

func emptyAndroidPolicy() androidPolicy {
	return androidPolicy{
		Version:          androidPolicyVersion,
		ExcludedPackages: []string{},
	}
}

func extractAndroidPolicy(path string) (androidPolicy, error) {
	data, err := os.ReadFile(P.Join(path, "config.yaml"))
	if err != nil {
		return androidPolicy{}, err
	}
	return extractAndroidPolicyFrom(data)
}

func extractAndroidPolicyFrom(data []byte) (androidPolicy, error) {
	plain, err := age.DecryptBytes(data)
	if err != nil {
		return androidPolicy{}, fmt.Errorf("decrypt config error: %w", err)
	}
	return parseAndroidPolicyYAML(plain)
}

func parseAndroidPolicyYAML(data []byte) (androidPolicy, error) {
	var doc map[string]any
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return androidPolicy{}, err
	}
	if doc == nil {
		return emptyAndroidPolicy(), nil
	}

	profile, ok := doc["x-getline-profile"]
	if !ok {
		return emptyAndroidPolicy(), nil
	}
	profileMap, err := asStringMap(profile, "x-getline-profile")
	if err != nil {
		return androidPolicy{}, err
	}

	android, ok := profileMap["android"]
	if !ok {
		return emptyAndroidPolicy(), nil
	}
	androidMap, err := asStringMap(android, "x-getline-profile.android")
	if err != nil {
		return androidPolicy{}, err
	}

	raw, ok := androidMap["excluded-packages"]
	if !ok {
		return emptyAndroidPolicy(), nil
	}
	packages, err := parseExcludedPackages(raw)
	if err != nil {
		return androidPolicy{}, err
	}
	return androidPolicy{
		Version:          androidPolicyVersion,
		ExcludedPackages: packages,
	}, nil
}

func asStringMap(value any, path string) (map[string]any, error) {
	if value == nil {
		return nil, fmt.Errorf("android-policy: %s: expected mapping, got null", path)
	}
	if typed, ok := value.(map[string]any); ok {
		return typed, nil
	}
	return nil, fmt.Errorf("android-policy: %s: expected mapping, got %s", path, yamlTypeName(value))
}

func parseExcludedPackages(raw any) ([]string, error) {
	const path = "x-getline-profile.android.excluded-packages"
	if raw == nil {
		return nil, fmt.Errorf("android-policy: %s: expected sequence, got null", path)
	}

	seq, err := asSequence(raw, path)
	if err != nil {
		return nil, err
	}

	out := make([]string, 0, len(seq))
	seen := make(map[string]struct{}, len(seq))
	for i, item := range seq {
		s, ok := item.(string)
		if !ok {
			return nil, fmt.Errorf("android-policy: %s[%d]: expected string, got %s", path, i, yamlTypeName(item))
		}
		s = strings.TrimSpace(s)
		if s == "" {
			return nil, fmt.Errorf("android-policy: %s[%d]: empty", path, i)
		}
		if _, dup := seen[s]; dup {
			continue
		}
		seen[s] = struct{}{}
		out = append(out, s)
	}
	return out, nil
}

func asSequence(value any, path string) ([]any, error) {
	switch typed := value.(type) {
	case []any:
		return typed, nil
	case []string:
		out := make([]any, len(typed))
		for i, item := range typed {
			out[i] = item
		}
		return out, nil
	default:
		return nil, fmt.Errorf("android-policy: %s: expected sequence, got %s", path, yamlTypeName(value))
	}
}

func yamlTypeName(value any) string {
	switch value.(type) {
	case nil:
		return "null"
	case string:
		return "string"
	case bool:
		return "bool"
	case int, int64, uint64, float64:
		return "scalar"
	case []any, []string:
		return "sequence"
	case map[string]any:
		return "mapping"
	default:
		return fmt.Sprintf("%T", value)
	}
}

func writeAndroidPolicy(path string, policy androidPolicy) error {
	if policy.Version == 0 {
		policy.Version = androidPolicyVersion
	}
	if policy.ExcludedPackages == nil {
		policy.ExcludedPackages = []string{}
	}

	data, err := json.Marshal(policy)
	if err != nil {
		return err
	}

	dest := P.Join(path, androidPolicyFile)
	tmp := P.Join(path, androidPolicyTmpFile)
	defer os.Remove(tmp)
	if err := os.WriteFile(tmp, data, 0600); err != nil {
		return err
	}
	if err := os.Rename(tmp, dest); err != nil {
		return err
	}
	return nil
}
