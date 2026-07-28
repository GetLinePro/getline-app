#!/usr/bin/env bash
set -euo pipefail

readonly upstream_api="https://api.github.com/repos/MetaCubeX/meta-rules-dat"
readonly assets_dir="app/src/main/assets"
readonly manifest="gradle/geodata.properties"
readonly assets=(
  "geoip.metadb:geoip.metadb"
  "geosite.dat:geosite.dat"
  "GeoLite2-ASN.mmdb:ASN.mmdb"
  "BundleMRS.7z:BundleMRS.7z"
)

request_headers=(-H "Accept: application/vnd.github+json")
download_headers=(-H "Accept: application/octet-stream")
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  request_headers+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  download_headers+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

curl -fsSL "${request_headers[@]}" \
  "${upstream_api}/git/ref/tags/latest" > "${tmp_dir}/ref-before.json"
curl -fsSL "${request_headers[@]}" \
  "${upstream_api}/releases/tags/latest" > "${tmp_dir}/release.json"
curl -fsSL "${request_headers[@]}" \
  "${upstream_api}/git/ref/tags/latest" > "${tmp_dir}/ref-after.json"

release_id="$(jq -er '.id' "${tmp_dir}/release.json")"
release_name="$(jq -er '.name' "${tmp_dir}/release.json")"
published_at="$(jq -er '.published_at' "${tmp_dir}/release.json")"
upstream_commit="$(jq -er '.object.sha' "${tmp_dir}/ref-before.json")"
upstream_commit_after="$(jq -er '.object.sha' "${tmp_dir}/ref-after.json")"
if [[ "$upstream_commit" != "$upstream_commit_after" ]]; then
  echo "The mutable upstream tag moved during the update; rerun the task." >&2
  exit 1
fi

mkdir -p "$assets_dir"

manifest_tmp="${tmp_dir}/geodata.properties"
{
  printf 'upstream.repository=https://github.com/MetaCubeX/meta-rules-dat\n'
  printf 'upstream.commit=%s\n' "$upstream_commit"
  printf 'release.id=%s\n' "$release_id"
  printf 'release.name=%s\n' "$release_name"
  printf 'release.publishedAt=%s\n' "$published_at"
  printf 'file.count=%s\n' "${#assets[@]}"
} > "$manifest_tmp"

for index in "${!assets[@]}"; do
  IFS=: read -r asset_name output_name <<< "${assets[$index]}"
  asset_json="$(jq -cer --arg name "$asset_name" \
    '.assets[] | select(.name == $name)' "${tmp_dir}/release.json")"
  asset_id="$(jq -er '.id' <<< "$asset_json")"
  asset_size="$(jq -er '.size' <<< "$asset_json")"
  asset_url="$(jq -er '.url' <<< "$asset_json")"
  asset_digest="$(jq -er '.digest' <<< "$asset_json")"
  expected_sha256="${asset_digest#sha256:}"
  output_tmp="${tmp_dir}/${output_name}"

  curl -fsSL "${download_headers[@]}" "$asset_url" -o "$output_tmp"
  actual_sha256="$(sha256sum "$output_tmp" | cut -d' ' -f1)"
  if [[ "$actual_sha256" != "$expected_sha256" ]]; then
    echo "SHA-256 mismatch for ${asset_name}: expected ${expected_sha256}, got ${actual_sha256}" >&2
    exit 1
  fi
  if [[ "$(stat -c '%s' "$output_tmp")" != "$asset_size" ]]; then
    echo "Size mismatch for ${asset_name}" >&2
    exit 1
  fi

  mv "$output_tmp" "${assets_dir}/${output_name}"
  {
    printf 'file.%s.asset=%s\n' "$index" "$asset_name"
    printf 'file.%s.output=%s\n' "$index" "$output_name"
    printf 'file.%s.size=%s\n' "$index" "$asset_size"
    printf 'file.%s.url=%s\n' "$index" "$asset_url"
    printf 'file.%s.sha256=%s\n' "$index" "$expected_sha256"
  } >> "$manifest_tmp"
done

mv "$manifest_tmp" "$manifest"
echo "Updated pinned geodata to ${release_name} (${upstream_commit})."
echo "Review and commit gradle/geodata.properties with app/src/main/assets/ in one PR."
