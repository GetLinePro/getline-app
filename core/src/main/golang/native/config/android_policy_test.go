package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/metacubex/mihomo/component/age"
)

func TestExtractAndroidPolicyAbsentIsEmpty(t *testing.T) {
	got, err := extractAndroidPolicyFrom([]byte(catalogSampleYAML))
	if err != nil {
		t.Fatal(err)
	}
	assertEmptyPolicy(t, got)
}

func TestExtractAndroidPolicyPresentTrimsAndDeduplicates(t *testing.T) {
	body := `
x-getline-profile:
  schema: ignored
  kind: ignored
  android:
    excluded-packages:
      - "  com.example.one  "
      - com.example.two
      - com.example.one
` + catalogSampleYAML
	got, err := extractAndroidPolicyFrom([]byte(body))
	if err != nil {
		t.Fatal(err)
	}
	if got.Version != androidPolicyVersion {
		t.Fatalf("version = %d", got.Version)
	}
	want := []string{"com.example.one", "com.example.two"}
	if len(got.ExcludedPackages) != 2 || got.ExcludedPackages[0] != want[0] || got.ExcludedPackages[1] != want[1] {
		t.Fatalf("excluded = %#v, want %#v", got.ExcludedPackages, want)
	}
}

func TestExtractAndroidPolicyRejectsMalformedField(t *testing.T) {
	cases := map[string]string{
		"explicit null": `
x-getline-profile:
  android:
    excluded-packages: null
` + catalogSampleYAML,
		"scalar": `
x-getline-profile:
  android:
    excluded-packages: com.example.app
` + catalogSampleYAML,
		"mapping": `
x-getline-profile:
  android:
    excluded-packages:
      name: com.example.app
` + catalogSampleYAML,
		"non-string element": `
x-getline-profile:
  android:
    excluded-packages:
      - 1
` + catalogSampleYAML,
		"empty after trim": `
x-getline-profile:
  android:
    excluded-packages:
      - "   "
` + catalogSampleYAML,
		"profile null": `
x-getline-profile: null
` + catalogSampleYAML,
		"android scalar": `
x-getline-profile:
  android: yes
` + catalogSampleYAML,
	}

	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			_, err := extractAndroidPolicyFrom([]byte(body))
			if err == nil {
				t.Fatal("expected error")
			}
			if !strings.Contains(err.Error(), "android-policy:") {
				t.Fatalf("error %q does not name android-policy", err)
			}
		})
	}
}

func TestExtractAndroidPolicyEmptySequenceIsEmpty(t *testing.T) {
	body := `
x-getline-profile:
  android:
    excluded-packages: []
` + catalogSampleYAML
	got, err := extractAndroidPolicyFrom([]byte(body))
	if err != nil {
		t.Fatal(err)
	}
	assertEmptyPolicy(t, got)
}

func TestValidateAndPrepareWritesEmptyAndroidPolicyWhenAbsent(t *testing.T) {
	profile := prepareProfile(t, catalogSampleYAML)
	assertEmptyPolicy(t, readAndroidPolicy(t, profile))
}

func TestValidateAndPrepareWritesNormalizedAndroidPolicy(t *testing.T) {
	body := `
x-getline-profile:
  android:
    excluded-packages:
      - " com.example.one "
      - com.example.two
      - com.example.one
` + catalogSampleYAML
	profile := prepareProfile(t, body)
	got := readAndroidPolicy(t, profile)
	if got.Version != androidPolicyVersion {
		t.Fatalf("version = %d", got.Version)
	}
	if len(got.ExcludedPackages) != 2 || got.ExcludedPackages[0] != "com.example.one" || got.ExcludedPackages[1] != "com.example.two" {
		t.Fatalf("excluded = %#v", got.ExcludedPackages)
	}
}

func TestValidateAndPrepareAbsentOverwritesStaleAndroidPolicy(t *testing.T) {
	root := t.TempDir()
	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(profile, 0700); err != nil {
		t.Fatal(err)
	}
	stale := []byte(`{"version":1,"excludedPackages":["com.stale.app"]}`)
	if err := os.WriteFile(filepath.Join(profile, androidPolicyFile), stale, 0600); err != nil {
		t.Fatal(err)
	}

	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(catalogSampleYAML), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}
	assertEmptyPolicy(t, readAndroidPolicy(t, profile))
}

func TestValidateAndPrepareRejectsMalformedAndroidPolicyWithoutSidecarWrite(t *testing.T) {
	root := t.TempDir()
	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(profile, 0700); err != nil {
		t.Fatal(err)
	}
	stale := []byte(`{"version":1,"excludedPackages":["com.stale.app"]}`)
	if err := os.WriteFile(filepath.Join(profile, androidPolicyFile), stale, 0600); err != nil {
		t.Fatal(err)
	}

	body := `
x-getline-profile:
  android:
    excluded-packages: null
` + catalogSampleYAML
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err == nil {
		t.Fatal("malformed policy unexpectedly accepted")
	}
	got := readAndroidPolicy(t, profile)
	if len(got.ExcludedPackages) != 1 || got.ExcludedPackages[0] != "com.stale.app" {
		t.Fatalf("stale sidecar was replaced: %#v", got)
	}
}

func TestWriteAndroidPolicyRemovesTmpOnWriteFileError(t *testing.T) {
	dir := t.TempDir()
	tmp := filepath.Join(dir, androidPolicyTmpFile)
	if err := os.Mkdir(tmp, 0700); err != nil {
		t.Fatal(err)
	}

	err := writeAndroidPolicy(dir, emptyAndroidPolicy())
	if err == nil {
		t.Fatal("expected write failure against tmp directory")
	}
	if _, statErr := os.Stat(tmp); !os.IsNotExist(statErr) {
		t.Fatalf("tmp left behind after WriteFile error: %v", statErr)
	}
}

func TestWriteAndroidPolicyRemovesTmpOnRenameError(t *testing.T) {
	dir := t.TempDir()
	dest := filepath.Join(dir, androidPolicyFile)
	if err := os.Mkdir(dest, 0700); err != nil {
		t.Fatal(err)
	}

	err := writeAndroidPolicy(dir, emptyAndroidPolicy())
	if err == nil {
		t.Fatal("expected rename failure against dest directory")
	}
	if _, statErr := os.Stat(filepath.Join(dir, androidPolicyTmpFile)); !os.IsNotExist(statErr) {
		t.Fatalf("tmp left behind after rename error: %v", statErr)
	}
}

func TestValidateAndPrepareFailsWhenAndroidPolicyUnwritable(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(catalogSampleYAML), 0600); err != nil {
		t.Fatal(err)
	}

	profile := filepath.Join(root, "profile")
	if err := os.MkdirAll(filepath.Join(profile, androidPolicyFile), 0700); err != nil {
		t.Fatal(err)
	}

	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err == nil {
		t.Fatal("policy write must fail import")
	}
	if _, statErr := os.Stat(filepath.Join(profile, androidPolicyTmpFile)); !os.IsNotExist(statErr) {
		t.Fatalf("tmp policy left behind: %v", statErr)
	}
}

func TestValidateAndPrepareAgeEncryptedYAMLWritesSameSidecar(t *testing.T) {
	secret, public, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	SetGlobalSecretKeys(secret)
	t.Cleanup(func() { SetGlobalSecretKeys() })

	body := `
x-getline-profile:
  android:
    excluded-packages:
      - com.example.one
      - com.example.two
` + catalogSampleYAML
	encrypted, err := age.EncryptBytes([]byte(body), public)
	if err != nil {
		t.Fatal(err)
	}

	plainProfile := prepareProfile(t, body)
	encryptedProfile := prepareProfile(t, string(encrypted))

	plain := readAndroidPolicy(t, plainProfile)
	got := readAndroidPolicy(t, encryptedProfile)
	if plain.Version != got.Version {
		t.Fatalf("version plain=%d encrypted=%d", plain.Version, got.Version)
	}
	if len(plain.ExcludedPackages) != len(got.ExcludedPackages) {
		t.Fatalf("excluded plain=%#v encrypted=%#v", plain.ExcludedPackages, got.ExcludedPackages)
	}
	for i := range plain.ExcludedPackages {
		if plain.ExcludedPackages[i] != got.ExcludedPackages[i] {
			t.Fatalf("excluded plain=%#v encrypted=%#v", plain.ExcludedPackages, got.ExcludedPackages)
		}
	}
}

func prepareProfile(t *testing.T, body string) string {
	t.Helper()
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	if err := os.WriteFile(localFile, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	profile := filepath.Join(root, "profile")
	if err := ValidateAndPrepareLocalConfig(profile, localFile, "", "", func(string) {}); err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}
	return profile
}

func readAndroidPolicy(t *testing.T, profile string) androidPolicy {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(profile, androidPolicyFile))
	if err != nil {
		t.Fatalf("read android policy: %v", err)
	}
	var policy androidPolicy
	if err := json.Unmarshal(raw, &policy); err != nil {
		t.Fatalf("decode android policy: %v", err)
	}
	if policy.ExcludedPackages == nil {
		t.Fatal("excludedPackages marshaled as null")
	}
	return policy
}

func assertEmptyPolicy(t *testing.T, policy androidPolicy) {
	t.Helper()
	if policy.Version != androidPolicyVersion {
		t.Fatalf("version = %d", policy.Version)
	}
	if len(policy.ExcludedPackages) != 0 {
		t.Fatalf("excluded = %#v, want empty", policy.ExcludedPackages)
	}
}
