// S1 e2e mock backend for native session happy path.
//
// Serves the minimal API surface needed to prove:
//   native PKCE start → mock provider → package-id callback code
//   → POST /api/auth/native/exchange → native session
//   → GET /api/subscriptions → GET /sub/e2e import → Home
//
// Device-key routes remain for email OTP handoff tests.
// Does not call bot.getline.pro or backend-app.
package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	// Fixed S0/S1 web token for email OTP / device-key path.
	s0AuthToken = "s0-auth-token"

	// Stable device_key issued after matching web Bearer (S1).
	s1DeviceKey = "s1-device-key"

	// One-time native OAuth code issued by mock provider Success.
	s1NativeCode = "s1-native-auth-code"

	// Native session tokens from successful native exchange / device-key exchange.
	s1NativeAccess  = "s1-native-access-token"
	s1NativeRefresh = "s1-native-refresh-token"
	s1ExpiresIn     = 3600

	// Optional refresh stub returns a new pair; both access tokens accepted.
	s1NativeAccessRefreshed  = "s1-native-access-token-refreshed"
	s1NativeRefreshRefreshed = "s1-native-refresh-token-refreshed"

	authStageOrigin = "https://auth.stage.getline.pro"
	mockGoogleURL   = authStageOrigin + "/__mock__/google"
	// alphaE2eDebug applicationId private-use callback (prod whitelist excludes this).
	nativeSuccessCallback = "pro.getline.vpn.alpha.e2e.debug:/oauth2redirect?code=" + s1NativeCode

	subscriptionLink = "https://app.stage.getline.pro/sub/e2e"
)

// In-process state: last issued device_key and last PKCE challenge from /start.
var (
	stateMu           sync.Mutex
	issuedDeviceKey   string
	pendingChallenge  string // S256 challenge from last /start with app_redirect
	pendingCodeIssued bool   // true after mock provider issues s1NativeCode
)

func main() {
	addr := listenAddr()
	mux := http.NewServeMux()

	// Native PKCE + mock provider surface.
	mux.HandleFunc("GET /__health", handleHealth)
	mux.HandleFunc("GET /api/auth/google/start", handleGoogleStart)
	mux.HandleFunc("GET /api/auth/telegram-oidc/start", handleTelegramStart)
	mux.HandleFunc("GET /android-auth/google", handleGoogleTrampoline)
	mux.HandleFunc("GET /__mock__/google", handleMockGoogle)
	mux.HandleFunc("GET /", handleCompletionRoot)
	mux.HandleFunc("POST /api/auth/native/exchange", handleNativeExchange)

	// S1 surface (native session + subscription import + email device-key).
	mux.HandleFunc("GET /api/auth/me", handleMe)
	mux.HandleFunc("GET /api/auth/device-key/generate", handleDeviceKeyGenerate)
	mux.HandleFunc("POST /api/auth/device-key/exchange", handleDeviceKeyExchange)
	mux.HandleFunc("GET /api/dashboard", handleDashboard)
	mux.HandleFunc("POST /api/dashboard/trial", handleActivateTrial)
	mux.HandleFunc("GET /api/subscriptions", handleSubscriptions)
	mux.HandleFunc("GET /sub/e2e", handleSubscriptionYAML)
	mux.HandleFunc("POST /api/auth/native/refresh", handleNativeRefresh)

	// ReadTimeout bounds full request (headers + body) so slow/hanging POST
	// bodies on exchange/refresh cannot pin handler goroutines indefinitely.
	// WriteTimeout covers JSON/YAML responses. ReadHeaderTimeout stays lower.
	srv := &http.Server{
		Addr:              addr,
		Handler:           logRequests(mux),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	log.Printf("e2e-mock S1 listening on %s", addr)
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("listen: %v", err)
	}
}

func listenAddr() string {
	if v := strings.TrimSpace(os.Getenv("LISTEN_ADDR")); v != "" {
		return v
	}
	return ":8080"
}

func logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		log.Printf("%s %s source=%s %s", r.Method, r.URL.Path, requestSource(r), time.Since(start).Round(time.Millisecond))
	})
}

// requestSource tags log lines so API smoke is not confused with app traffic.
// Optional header X-E2E-Client: api-smoke → source=api_smoke.
// Android and other clients omit the header → source=app.
// Never required by production RWP; mock-only observability.
func requestSource(r *http.Request) string {
	if strings.EqualFold(strings.TrimSpace(r.Header.Get("X-E2E-Client")), "api-smoke") {
		return "api_smoke"
	}
	return "app"
}

func handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"status":  "ok",
		"service": "e2e-mock",
		"slice":   "S1",
	})
}

// Exact shape expected by RwpGetLineAuthApi.startBrowserAuth: {"auth_url":"..."}.
// When app_redirect + code_challenge are present, stores challenge for native/exchange.
func handleGoogleStart(w http.ResponseWriter, r *http.Request) {
	rememberPkceFromStart(r)
	writeJSON(w, http.StatusOK, map[string]string{
		"auth_url": mockGoogleURL,
	})
}

func handleTelegramStart(w http.ResponseWriter, r *http.Request) {
	// Telegram /start is fail-open without challenge on prod; mock always accepts
	// and still records PKCE when the app sends it (client must always send S256).
	rememberPkceFromStart(r)
	writeJSON(w, http.StatusOK, map[string]string{
		"auth_url": mockGoogleURL, // reuse mock provider page for e2e
	})
}

func rememberPkceFromStart(r *http.Request) {
	q := r.URL.Query()
	challenge := strings.TrimSpace(q.Get("code_challenge"))
	redirect := strings.TrimSpace(q.Get("app_redirect"))
	if challenge == "" || redirect == "" {
		return
	}
	stateMu.Lock()
	pendingChallenge = challenge
	pendingCodeIssued = false
	stateMu.Unlock()
	log.Printf(
		"pkce_start_recorded source=%s app_redirect_present=%t challenge_len=%d",
		requestSource(r),
		true,
		len(challenge),
	)
}

// Same-origin trampoline the app launches instead of calling google/start from
// its own process. Production needs it so the edge can mark the browser before
// the provider leg; stage mirrors it so both flavors exercise one client path.
func handleGoogleTrampoline(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(googleTrampolineHTML))
}

func handleMockGoogle(w http.ResponseWriter, r *http.Request) {
	// Mark one-time code as issued when the mock provider page is opened.
	stateMu.Lock()
	if pendingChallenge != "" {
		pendingCodeIssued = true
	}
	stateMu.Unlock()

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(mockGoogleHTML))
}

// Auth Tab completion hits path "/" (fragment is not sent to the server).
func handleCompletionRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(completionHTML))
}

// Optional identity probe in establishFromWebToken (non-fatal on failure).
// Shape: fields actually read by RwpGetLineAuthApi.getCurrentUser.
func handleMe(w http.ResponseWriter, r *http.Request) {
	bearerPresent, webTokenMatches := inspectWebBearer(r)

	log.Printf(
		"me_requested source=%s bearer_present=%t web_token_matches=%t",
		requestSource(r),
		bearerPresent,
		webTokenMatches,
	)

	if !bearerPresent || !webTokenMatches {
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}

	// Synthetic identity: customer_id ← e2e-user, username ← e2e email.
	// Only fields confirmed by RwpGetLineAuthApi / spike contract.
	writeJSON(w, http.StatusOK, map[string]any{
		"customer_id": "e2e-user",
		"username":    "e2e@getline.invalid",
		"first_name":  "E2E",
		"role":        "user",
	})
}

// App calls generate after parsing auth_token (me is optional).
// Stores device_key in process memory for the subsequent exchange.
func handleDeviceKeyGenerate(w http.ResponseWriter, r *http.Request) {
	bearerPresent, webTokenMatches := inspectWebBearer(r)

	if !bearerPresent || !webTokenMatches {
		log.Printf(
			"device_key_issued source=%s bearer_present=%t web_token_matches=%t device_key_issued=%t",
			requestSource(r),
			bearerPresent,
			webTokenMatches,
			false,
		)
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}

	stateMu.Lock()
	issuedDeviceKey = s1DeviceKey
	stateMu.Unlock()

	log.Printf(
		"device_key_issued source=%s bearer_present=%t web_token_matches=%t device_key_issued=%t",
		requestSource(r),
		true,
		true,
		true,
	)

	// Controlled shape used by RwpGetLineAuthApi.generateDeviceKey.
	writeJSON(w, http.StatusOK, map[string]string{
		"device_key": s1DeviceKey,
	})
}

// App body (RwpGetLineAuthApi.exchangeDeviceKey): {"device_key":"..."}.
// Real client does not send Authorization on exchange (matches live RWP).
// Bearer is inspected if present; success requires previously issued device_key.
func handleDeviceKeyExchange(w http.ResponseWriter, r *http.Request) {
	bearerPresent, webTokenMatches := inspectWebBearer(r)

	src := requestSource(r)

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		log.Printf(
			"device_key_exchange source=%s bearer_present=%t web_token_matches=%t device_key_matches=%t error=body_read",
			src,
			bearerPresent,
			webTokenMatches,
			false,
		)
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "Invalid request body",
		})
		return
	}

	var payload struct {
		DeviceKey string `json:"device_key"`
	}
	if err := json.Unmarshal(body, &payload); err != nil || strings.TrimSpace(payload.DeviceKey) == "" {
		log.Printf(
			"device_key_exchange source=%s bearer_present=%t web_token_matches=%t device_key_matches=%t error=invalid_body",
			src,
			bearerPresent,
			webTokenMatches,
			false,
		)
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "device_key required",
		})
		return
	}

	stateMu.Lock()
	expected := issuedDeviceKey
	stateMu.Unlock()

	deviceKeyMatches := expected != "" && payload.DeviceKey == expected

	// If a Bearer is supplied and wrong, reject (controlled 401).
	// Absence is OK — app/RWP exchange does not send web Bearer.
	if bearerPresent && !webTokenMatches {
		log.Printf(
			"device_key_exchange source=%s bearer_present=%t web_token_matches=%t device_key_matches=%t",
			src,
			bearerPresent,
			webTokenMatches,
			deviceKeyMatches,
		)
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}

	if !deviceKeyMatches {
		log.Printf(
			"device_key_exchange source=%s bearer_present=%t web_token_matches=%t device_key_matches=%t",
			src,
			bearerPresent,
			webTokenMatches,
			false,
		)
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "Invalid device_key",
		})
		return
	}

	log.Printf(
		"device_key_exchange_succeeded source=%s bearer_present=%t web_token_matches=%t device_key_matches=%t",
		src,
		bearerPresent,
		webTokenMatches,
		true,
	)

	// Exact shape expected by RwpGetLineAuthApi.toNativeSession.
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  s1NativeAccess,
		"refresh_token": s1NativeRefresh,
		"expires_in":    s1ExpiresIn,
	})
}

// Requires native access Bearer (from exchange). Shape from SubscriptionsJson tests.
// On prod GET dashboard may auto-activate a trial; the mock always serves a
// subscription already, so this only needs to exist and report settled flags.
// Explicit POST /api/dashboard/trial is registered for contract parity.
func handleDashboard(w http.ResponseWriter, r *http.Request) {
	nativePresent, nativeMatches := inspectNativeBearer(r)

	log.Printf(
		"dashboard_requested source=%s native_bearer_present=%t native_token_matches=%t",
		requestSource(r),
		nativePresent,
		nativeMatches,
	)

	if !nativePresent || !nativeMatches {
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"trial_enabled":        true,
		"trial_available":      false,
		"trial_auto_activated": false,
		"trial_days":           3,
		"plans_count":          1,
		"banners":              []any{},
	})
}

// OpenAPI free-trial mutation. Mock subscriptions already exist; accept and
// return empty success so explicit client activation does not 404.
func handleActivateTrial(w http.ResponseWriter, r *http.Request) {
	nativePresent, nativeMatches := inspectNativeBearer(r)
	log.Printf(
		"activate_trial_requested source=%s native_bearer_present=%t native_token_matches=%t",
		requestSource(r),
		nativePresent,
		nativeMatches,
	)
	if !nativePresent || !nativeMatches {
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{})
}

func handleSubscriptions(w http.ResponseWriter, r *http.Request) {
	nativePresent, nativeMatches := inspectNativeBearer(r)

	log.Printf(
		"subscriptions_requested source=%s native_bearer_present=%t native_token_matches=%t",
		requestSource(r),
		nativePresent,
		nativeMatches,
	)

	if !nativePresent || !nativeMatches {
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Authentication required",
		})
		return
	}

	// Future expiry relative to now so days_left stays positive across deploys.
	expireAt := time.Now().UTC().Add(30 * 24 * time.Hour).Truncate(time.Second).
		Format("2006-01-02T15:04:05.000000Z")

	writeJSON(w, http.StatusOK, map[string]any{
		"autopay_available": false,
		"subscriptions": []map[string]any{
			{
				"id":                 1,
				"name":               "E2E Primary",
				"is_primary":         true,
				"is_active":          true,
				"kind":               "paid",
				"plan_id":            1,
				"plan_name":          "E2E Plan",
				"plan_type":          "subscription",
				"plan_archived":      false,
				"renewal_disabled":   false,
				"device_limit":        3,
				"total_device_limit":  3,
				"devices_count":      0,
				"expire_at":          expireAt,
				"days_left":          30,
				"subscription_link":  subscriptionLink,
				"autopay_enabled":    false,
				"traffic": map[string]any{
					"used_bytes":   0,
					"limit_bytes":  16106127360, // 15 GiB
					"used_percent": 0.0,
					"is_unlimited": false,
				},
				"devices": []any{},
			},
		},
	})
}

// Minimal Clash/Mihomo YAML accepted by CMFA ProfileProcessor / Clash.fetchAndValid.
// No working external tunnel required for S1 — import + activate + Home only.
func handleSubscriptionYAML(w http.ResponseWriter, r *http.Request) {
	log.Printf("subscription_yaml_requested source=%s", requestSource(r))

	w.Header().Set("Content-Type", "text/yaml; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	// Common subscription metadata headers (optional; CMFA tolerates absence).
	w.Header().Set("Subscription-Userinfo", "upload=0; download=0; total=16106127360; expire=0")
	w.Header().Set("X-GetLine-Tag", "PAID")
	w.Header().Set("X-GetLine-Status", "Active")
	w.Header().Set("X-GetLine-Device-Limit", "10")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(e2eClashYAML))
}

// POST /api/auth/native/exchange — body {"code","code_verifier"}.
// Verifies S256(verifier) against the challenge stored at /start and that
// mock provider has issued the one-time code.
func handleNativeExchange(w http.ResponseWriter, r *http.Request) {
	src := requestSource(r)
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
		return
	}
	var payload struct {
		Code         string `json:"code"`
		CodeVerifier string `json:"code_verifier"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "Invalid request body"})
		return
	}
	code := strings.TrimSpace(payload.Code)
	verifier := strings.TrimSpace(payload.CodeVerifier)
	if code == "" || verifier == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "code and code_verifier required"})
		return
	}

	stateMu.Lock()
	challenge := pendingChallenge
	issued := pendingCodeIssued
	stateMu.Unlock()

	if code != s1NativeCode || !issued {
		log.Printf("native_exchange source=%s code_ok=%t issued=%t", src, code == s1NativeCode, issued)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_grant"})
		return
	}
	if challenge == "" || s256Challenge(verifier) != challenge {
		log.Printf("native_exchange source=%s verifier_ok=false", src)
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid_grant"})
		return
	}

	// One-time code: clear issued flag so replay fails.
	stateMu.Lock()
	pendingCodeIssued = false
	pendingChallenge = ""
	stateMu.Unlock()

	log.Printf("native_exchange_succeeded source=%s", src)
	writeJSON(w, http.StatusOK, map[string]any{
		"access_token":  s1NativeAccess,
		"refresh_token": s1NativeRefresh,
		"expires_in":    s1ExpiresIn,
	})
}

func s256Challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// Simple valid stub — happy path does not call refresh.
func handleNativeRefresh(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "Invalid request body",
		})
		return
	}

	var payload struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "Invalid request body",
		})
		return
	}

	token := strings.TrimSpace(payload.RefreshToken)
	switch token {
	case s1NativeRefresh, s1NativeRefreshRefreshed:
		writeJSON(w, http.StatusOK, map[string]any{
			"access_token":  s1NativeAccessRefreshed,
			"refresh_token": s1NativeRefreshRefreshed,
			"expires_in":    s1ExpiresIn,
		})
	default:
		writeJSON(w, http.StatusUnauthorized, map[string]string{
			"error": "Invalid refresh_token",
		})
	}
}

// inspectWebBearer checks Authorization against s0-auth-token without logging the value.
func inspectWebBearer(r *http.Request) (present bool, matches bool) {
	return inspectBearer(r, s0AuthToken)
}

// inspectNativeBearer accepts exchange or refreshed native access tokens.
func inspectNativeBearer(r *http.Request) (present bool, matches bool) {
	auth := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(auth, prefix) {
		return false, false
	}
	token := strings.TrimSpace(strings.TrimPrefix(auth, prefix))
	if token == "" {
		return true, false
	}
	ok := token == s1NativeAccess || token == s1NativeAccessRefreshed
	return true, ok
}

func inspectBearer(r *http.Request, expected string) (present bool, matches bool) {
	auth := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(auth, prefix) {
		return false, false
	}
	token := strings.TrimSpace(strings.TrimPrefix(auth, prefix))
	if token == "" {
		return true, false
	}
	return true, token == expected
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(body); err != nil {
		log.Printf("writeJSON: %v", err)
	}
}

const e2eClashYAML = `# e2e-mock S1 — minimal valid Clash/Mihomo profile (no live tunnel)
mixed-port: 7890
allow-lan: false
mode: rule
log-level: info
ipv6: false

proxies:
  - name: "e2e-direct"
    type: direct

proxy-groups:
  - name: "GetLine"
    type: select
    proxies:
      - e2e-direct
      - DIRECT
      - REJECT

rules:
  - MATCH,GetLine
`

const mockGoogleHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>S1 Mock Google</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 2rem; background: #1a1b1e; color: #e8e8e8; }
    a.button {
      display: inline-block; margin-top: 1rem; padding: 0.75rem 1.25rem;
      background: #3b82f6; color: #fff; text-decoration: none; border-radius: 8px;
    }
  </style>
</head>
<body>
  <h1>S1 mock Google sign-in</h1>
  <p>Not a real OAuth provider. Success completes via native package-id callback.</p>
  <p><a class="button" href="` + nativeSuccessCallback + `">Success</a></p>
</body>
</html>
`

// Mirrors docs/spikes/android-auth/google-trampoline.html, with the provider
// origin check pointed at the stage mock instead of accounts.google.com.
const googleTrampolineHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>S1 Google Trampoline</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 2rem; background: #1a1b1e; color: #e8e8e8; }
  </style>
</head>
<body>
  <p>Starting Google sign-in…</p>
  <script>
    (async () => {
      try {
        const response = await fetch("/api/auth/google/start?intent=register", {
          headers: { Accept: "application/json" },
          credentials: "same-origin",
        });
        if (!response.ok) {
          throw new Error("start failed");
        }
        const payload = await response.json();
        if (!payload || !payload.auth_url) {
          throw new Error("auth_url missing");
        }
        if (new URL(payload.auth_url).origin !== "` + authStageOrigin + `") {
          throw new Error("unexpected provider origin");
        }
        window.location.replace(payload.auth_url);
      } catch (e) {
        document.body.textContent =
          "Could not start Google sign-in. Close this tab and retry in the app.";
      }
    })();
  </script>
</body>
</html>
`

const completionHTML = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>S1 Auth Callback</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 2rem; background: #1a1b1e; color: #e8e8e8; }
  </style>
</head>
<body>
  <p>S1 authentication callback host. Auth Tab should close on this navigation.</p>
</body>
</html>
`
