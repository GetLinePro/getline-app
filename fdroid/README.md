# F-Droid metadata gate

Do not submit an F-Droid recipe until a public GetLine VPN release tag contains
these reproducible-build changes. The existing `v2.11.32` tag predates the
GetLine product commits and is not a valid F-Droid source tag for this app.

Once that tag exists, prepare the recipe in the public `fdroiddata` repository
with these locked facts:

- application ID: `pro.getline.vpn`
- license: `GPL-3.0-only`
- source: `https://github.com/momai/getline-app`
- build flavor: `metaProdRelease`
- build command: `./gradlew --offline --no-daemon :app:assembleMetaProdRelease`
- submodules: required
- JDK: Temurin `21.0.10+7.0.LTS`
- Go: MetaCubeX patched Go `1.26` Linux amd64 asset `469676048`,
  SHA-256 `03a2db2ecd724909798e8742cfcd5973f7a6eb6bb240854c7d599743e684922e`,
  with the additional patches in `.github/patch/`
- Android NDK: `29.0.14206865`
- Android Build Tools: `36.0.0`
- CMake: `3.22.1`

The recipe must set `SOURCE_DATE_EPOCH` to the public tag commit timestamp and
must not provide signing material. Confirm the exact tag, version code, version
name, source tarball/commit, and the actual unsigned APK comparison before
claiming reproducibility.
