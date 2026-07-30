#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
keystore="$repo_root/.act-release.keystore"
secrets_file="$repo_root/.act.secrets"
vars_file="$repo_root/.act.vars"
alias_name="act-release"
complete=0

cleanup_incomplete_output() {
    if [[ "$complete" != "1" ]]; then
        rm -f -- "$keystore" "$secrets_file" "$vars_file"
    fi
}

for path in "$keystore" "$secrets_file" "$vars_file"; do
    if [[ -e "$path" ]]; then
        echo "Refusing to overwrite $path; remove the disposable files first." >&2
        exit 1
    fi
done

# Every output is known to be ours after the overwrite guard has passed.
trap cleanup_incomplete_output EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

umask 077
password="$(openssl rand -hex 16)"

keytool -genkeypair \
    -keystore "$keystore" \
    -storepass "$password" \
    -keypass "$password" \
    -alias "$alias_name" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 30 \
    -dname "CN=act release preflight, OU=local only, O=GetLineVPN, C=XX" \
    >/dev/null

fingerprint="$(
    keytool -list -v \
        -keystore "$keystore" \
        -storepass "$password" \
        -alias "$alias_name" |
        sed -n 's/^[[:space:]]*SHA256: //p' |
        head -n1
)"

if [[ -z "$fingerprint" ]]; then
    echo "Could not read the disposable certificate fingerprint." >&2
    exit 1
fi

keystore_base64="$(base64 -w0 "$keystore")"
{
    printf 'SIGNING_KEYSTORE_BASE64=%s\n' "$keystore_base64"
    printf 'SIGNING_STORE_PASSWORD=%s\n' "$password"
    printf 'SIGNING_KEY_ALIAS=%s\n' "$alias_name"
    printf 'SIGNING_KEY_PASSWORD=%s\n' "$password"
} >"$secrets_file"

printf 'EXPECTED_SIGNING_CERT_SHA256=%s\n' "$fingerprint" >"$vars_file"

complete=1
echo "Created ignored, disposable act signing material:"
echo "  $keystore"
echo "  $secrets_file"
echo "  $vars_file"
