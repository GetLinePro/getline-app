#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "usage: $0 RELEASE_JSON" >&2
  exit 2
}

[[ $# -eq 1 ]] || usage

release_json=$1

: "${TG_BOT_TOKEN:?TG_BOT_TOKEN is required}"
: "${TG_CHAT_ID:?TG_CHAT_ID is required}"

command -v jq >/dev/null || { echo "ERROR: jq is required" >&2; exit 1; }
command -v curl >/dev/null || { echo "ERROR: curl is required" >&2; exit 1; }

[[ -s "$release_json" ]] || {
  echo "ERROR: GitHub Release response is missing: $release_json" >&2
  exit 1
}

release_tag=$(jq -er '.tag_name | select(type == "string" and length > 0)' "$release_json")
release_title=$(jq -er '
  if (.name | type) == "string" and (.name | length) > 0 then .name
  else .tag_name
  end
' "$release_json")
release_body=$(jq -r '.body // ""' "$release_json")
release_url=$(jq -er '.html_url | select(type == "string" and length > 0)' "$release_json")

tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT

telegram_json() {
  local method=$1
  local payload=$2
  local response="$tmp_dir/${method}.json"

  if ! curl \
      --fail-with-body \
      --silent \
      --show-error \
      --request POST \
      "https://api.telegram.org/bot${TG_BOT_TOKEN}/${method}" \
      --header "Content-Type: application/json" \
      --data "$payload" \
      --output "$response"; then
    echo "ERROR: Telegram $method request failed" >&2
    return 1
  fi

  if ! jq -e '.ok == true' "$response" >/dev/null; then
    echo "ERROR: Telegram rejected $method request:" >&2
    jq -r '.description // "unknown Telegram API error"' "$response" >&2
    return 1
  fi
}

if [[ -n "$release_body" ]]; then
  message="🚀 ${release_title}

${release_body}

${release_url}"
else
  message="🚀 ${release_title}

${release_url}"
fi

# Telegram rejects messages over 4096 characters. Failing here preserves the
# complete, current Release notes instead of silently publishing a truncated
# announcement.
if [[ ${#message} -gt 4096 ]]; then
  echo "ERROR: release title/body/url exceed Telegram's 4096-character limit" >&2
  exit 1
fi

message_payload=$(jq -n --arg chat_id "$TG_CHAT_ID" --arg text "$message" '{chat_id: $chat_id, text: $text}')
telegram_json sendMessage "$message_payload"

echo "Announced $release_tag text to Telegram."
