#!/usr/bin/env bash
# Verify a signed agent release: each file's SHA-384 checksum and detached signature (from scripts/sign-release.sh).
#
#   scripts/verify-release.sh --pub <public key PEM or X.509 certificate PEM> [--fingerprint sha256:<hex>] file ...
#
# For each file it expects <file>.sha384 and <file>.sig next to it. --fingerprint pins the signing key: the SHA-256 of
# the public key the release publishes; without it, anyone who can replace the public key file could sign. Exits 0
# only when every file verifies.
set -euo pipefail

die() { echo "verify-release: $*" >&2; exit 1; }

PUB="" PIN=""
while [ $# -gt 0 ]; do
    case "$1" in
        --pub) PUB="${2:-}"; shift 2 ;;
        --fingerprint) PIN="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option $1" ;;
        *) break ;;
    esac
done
command -v openssl >/dev/null || die "openssl is not installed"
[ -n "$PUB" ] && [ -f "$PUB" ] || die "--pub <public key or certificate PEM> is required"
[ $# -gt 0 ] || die "no files to verify"

pubfile="$(mktemp)"; trap 'rm -f "$pubfile"' EXIT
if grep -q "BEGIN CERTIFICATE" "$PUB"; then
    openssl x509 -in "$PUB" -pubkey -noout > "$pubfile" 2>/dev/null || die "cannot read the certificate $PUB"
else
    openssl pkey -pubin -in "$PUB" -out "$pubfile" 2>/dev/null || die "cannot read the public key $PUB"
fi
keyfp="$(openssl pkey -pubin -in "$pubfile" -outform DER 2>/dev/null | openssl dgst -sha256 -r | cut -d' ' -f1)"
if [ -n "$PIN" ]; then
    want="$(printf '%s' "${PIN#sha256:}" | tr -d ':' | tr 'A-F' 'a-f')"
    [ "$want" = "$keyfp" ] || die "the public key's fingerprint sha256:$keyfp is not the pinned $PIN"
fi

fail=0
for f in "$@"; do
    name="$(basename "$f")"
    if [ ! -f "$f" ] || [ ! -f "$f.sha384" ] || [ ! -f "$f.sig" ]; then
        echo "FAIL $name: missing the file, $name.sha384 or $name.sig"; fail=1; continue
    fi
    want="$(cut -d' ' -f1 < "$f.sha384")"
    listed="$(awk '{print $2}' < "$f.sha384")"
    got="$(openssl dgst -sha384 -r "$f" | cut -d' ' -f1)"
    if [ "$listed" != "$name" ] || [ "$want" != "$got" ]; then
        echo "FAIL $name: SHA-384 $got does not match $name.sha384"; fail=1; continue
    fi
    if ! openssl dgst -sha384 -verify "$pubfile" -signature "$f.sig" "$f" >/dev/null 2>&1; then
        echo "FAIL $name: the signature does not verify with the key sha256:$keyfp"; fail=1; continue
    fi
    echo "OK   $name  sha384:$got"
done
[ $fail -eq 0 ] || die "verification FAILED"
echo "all files verified (key sha256:$keyfp)"
