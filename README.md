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

- Android 5.0+ (minimum)
- Android 7.0+ (recommended)
- `armeabi-v7a`, `arm64-v8a`, `x86` or `x86_64`

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

Application id: `pro.getline.vpn` (alpha adds `.alpha`; e2e adds `.e2e`; debug adds `.debug`).

### Automation

Package name: `pro.getline.vpn`

- Toggle VPN service  
  - Intent action: `${applicationId}.action.TOGGLE_CLASH` on `ExternalControlActivity`
- Start VPN service  
  - `${applicationId}.action.START_CLASH`
- Stop VPN service  
  - `${applicationId}.action.STOP_CLASH`
- Import a profile  
  - URL scheme `clash://install-config?url=<encoded URI>` or `clashmeta://install-config?url=<encoded URI>`

### License and attribution

This project is released under the [GNU General Public License v3](LICENSE).

It is a modified fork of CMFA and embeds Mihomo. Third-party license texts are in [NOTICE](NOTICE). Do not remove LICENSE, NOTICE, or upstream acknowledgements.
