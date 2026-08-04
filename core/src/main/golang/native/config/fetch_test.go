package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestValidateAndPrepareLocalConfigCopiesFileAndReportsHeaders(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "download.yaml")
	content := []byte("mixed-port: 7890\nproxies:\n  - name: test\n    type: socks5\n    server: 127.0.0.1\n    port: 1080\nproxy-groups: []\nrules: []\n")
	if err := os.WriteFile(localFile, content, 0600); err != nil {
		t.Fatal(err)
	}

	var statuses []Status
	err := ValidateAndPrepareLocalConfig(
		filepath.Join(root, "profile"),
		localFile,
		"upload=1; download=2; total=10; expire=2000000000",
		"1",
		func(value string) {
			var status Status
			if err := json.Unmarshal([]byte(value), &status); err != nil {
				t.Errorf("decode status: %v", err)
				return
			}
			statuses = append(statuses, status)
		},
	)
	if err != nil {
		t.Fatalf("ValidateAndPrepareLocalConfig: %v", err)
	}

	written, err := os.ReadFile(filepath.Join(root, "profile", "config.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if string(written) != string(content) {
		t.Fatalf("installed config differs:\n%s", written)
	}

	var subscription *Status
	for i := range statuses {
		if statuses[i].Action == "SubscriptionInfo" {
			subscription = &statuses[i]
			break
		}
	}
	if subscription == nil {
		t.Fatal("SubscriptionInfo status not reported")
	}
	if subscription.SubUpload == nil || *subscription.SubUpload != 1 {
		t.Fatalf("upload = %v", subscription.SubUpload)
	}
	if subscription.SubDownload == nil || *subscription.SubDownload != 2 {
		t.Fatalf("download = %v", subscription.SubDownload)
	}
	if subscription.SubTotal == nil || *subscription.SubTotal != 10 {
		t.Fatalf("total = %v", subscription.SubTotal)
	}
	wantInterval := int64(time.Hour / time.Millisecond)
	if subscription.SubUpdateInterval == nil || *subscription.SubUpdateInterval != wantInterval {
		t.Fatalf("update interval = %v, want %d", subscription.SubUpdateInterval, wantInterval)
	}
}

func TestValidateAndPrepareLocalConfigRejectsInvalidYaml(t *testing.T) {
	root := t.TempDir()
	localFile := filepath.Join(root, "broken.yaml")
	if err := os.WriteFile(localFile, []byte("proxies: [\n"), 0600); err != nil {
		t.Fatal(err)
	}

	err := ValidateAndPrepareLocalConfig(
		filepath.Join(root, "profile"),
		localFile,
		"",
		"",
		func(string) {},
	)
	if err == nil {
		t.Fatal("invalid YAML unexpectedly accepted")
	}
}
