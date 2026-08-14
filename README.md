## GetLine VPN

Official Android client for [GetLine](https://getline.pro) VPN.

GetLine VPN is a modified fork of [Clash Meta for Android (CMFA)](https://github.com/MetaCubeX/ClashMetaForAndroid), using the [Mihomo](https://github.com/MetaCubeX/mihomo) core. Licensed under **GNU GPLv3**.

- Account / cabinet: https://app.getline.pro/
- Support: https://t.me/GetLinePro
- Privacy (service): https://getline.pro/privacy.html
- App sources (this fork): https://github.com/momai/getline-app
- Upstream CMFA: https://github.com/MetaCubeX/ClashMetaForAndroid
- Upstream core: https://github.com/MetaCubeX/mihomo

### Feature (MVP)

- Import a GetLine subscription URL (or QR); open the web cabinet for account management
- Connect / disconnect VPN with a simple home screen
- Subscription refresh and server selection
- Advanced CMFA-derived settings for power users
- Safe diagnostics without leaking credentials in product UI copy

### Requirement

- Android 6.0+ (minimum, `minSdk 23`)
- Android 7.0+ (recommended)
- `armeabi-v7a`, `arm64-v8a`, `x86` or `x86_64`

### Relationship to upstream

CMFA is a general-purpose Mihomo client. This fork narrows it to a single
provider, so most of the delta sits on the boundary between product code and
the upstream client rather than inside either.

- `app/src/main/java/pro/getline/vpn/` — product layer, 82 Kotlin files:
  subscription import, account and auth flow, server list, diagnostics.
  Covered by 60 test files under `app/src/test/`.
- `getlineui/` — product UI module. It depends on `:common` only; `:core` and
  `:service` are deliberately out of reach for product code.
- `core/patches/mihomo/` — three patches carried against the Mihomo submodule:
  SSH outbound disabled (`no_ssh`), subscription redirects restricted, logrus
  output discarded under CMFA. `scripts/verify-mihomo-gate.sh` fails the build
  if the submodule tree stops matching them, so a forced submodule update
  cannot silently drop a patch.
- CMFA's own settings stay reachable as a diagnostic surface, not as product
  navigation.

### Build

1. Update submodules and apply Mihomo product patches

   ```bash
   git submodule update --init --recursive
   ./scripts/apply-mihomo-patches.sh
   ```

   Product deltas to Mihomo (e.g. SSH outbound disable / `no_ssh`) live in
   `core/patches/mihomo/` and are **not** in the upstream submodule commit.
   Gradle `core` builds also run this script automatically.

2. Install **JDK 21** (or Android Studio Embedded JDK), **Android SDK**, **CMake** and **Golang**

3. Create `local.properties` in the project root:

   ```properties
   sdk.dir=/path/to/android-sdk
   ```

4. Create `signing.properties` in the project root:

   ```properties
   keystore.path=/path/to/keystore/file
   keystore.password=<key store password>
   key.alias=<key alias>
   key.password=<key password>
   ```

5. Build

   ```bash
   ./gradlew app:assembleMetaProdRelease
   # side-channel alpha + production API hosts:
   ./gradlew app:assembleAlphaProdRelease
   # stage e2e (Auth Tab smoke against stage mock; alpha debug only):
   ./gradlew app:assembleAlphaE2eDebug
   ```

6. Check

   ```bash
   ./scripts/verify-mihomo-gate.sh
   ./gradlew :app:testAlphaProdDebugUnitTest :app:testAlphaE2eDebugUnitTest
   ```

   Both flavours are expected to stay green; auth changes in particular are not
   considered done until they are.

Application id: `pro.getline.vpn` (alpha adds `.alpha`; e2e adds `.e2e`; debug adds `.debug`).

### External import

Package name: `pro.getline.vpn`

- Import a GetLine subscription:
  - URL scheme `getline://install-config?url=<encoded HTTPS URI>`
  - the subscription host must be allowed for the build environment;
  - the app shows the host and requires confirmation before importing.

VPN start, stop and toggle actions are internal launcher shortcuts, not an
external automation API.

### Design notes

Reasoning for the parts that are not obvious from the diff is kept in the
repository:

- [`docs/spikes/android-auth/`](docs/spikes/android-auth/) — browser auth:
  Auth Tab and Custom Tabs, native PKCE, callback lifecycle, and why HTTPS
  completion is not served from the portal host.
- [`docs/refactor/`](docs/refactor/) (ru) — separating the product from CMFA,
  one slice at a time, including the options that were rejected and why.
- [`docs/engineering-decisions.md`](docs/engineering-decisions.md) — decisions
  that tend to get reopened: no `QUERY_ALL_PACKAGES`, what split tunnelling can
  and cannot detect, where new behaviour is allowed to land.
- [`docs/release-policy.md`](docs/release-policy.md) — why `versionCode` is not
  a build counter (F-Droid reproducibility) and what gates a release.

### Project roles

- **Project and service owner** — GetLine / Momai: the service itself, server
  infrastructure, domains, DNS, VPN nodes and payments.
- **Android client maintainer** — Konezumi: this repository.
- **Upstream core and client** — MetaCubeX: the Mihomo core and CMFA.

This repository covers the Android application only, and is developed
separately from the service it connects to. It holds no server configuration
and no credentials. The static auth assets it does carry
(`docs/spikes/android-auth/`) are the client's half of the contract; deploying
them, and everything behind them, belongs to the service owner. Certificate
fingerprints in `assetlinks.json.example` are placeholders — substitute the
ones for your own signing keys.

### License and attribution

This project is released under the [GNU General Public License v3](LICENSE).

It is a modified fork of CMFA and embeds Mihomo. Third-party license texts are in [NOTICE](NOTICE). Do not remove LICENSE, NOTICE, or upstream acknowledgements.
