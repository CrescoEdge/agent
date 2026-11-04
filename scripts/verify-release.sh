#!/usr/bin/env bash
# Verify a signed agent release (from scripts/sign-release.sh).
#
#   scripts/verify-release.sh --pub <public key PEM or X.509 certificate PEM> --fingerprint sha256:<hex>
#                             [--name <name>] [--version <version>] file ...
#
# For each file it expects <file>.manifest and <file>.sig next to it, and accepts the file only when:
#   - the public key is the pinned one (--fingerprint, required: the SHA-256 of the public key the release publishes;
#     a public key file alone proves nothing, since whoever can replace it can sign);
#   - <file>.sig is that key's signature of <file>.manifest;
#   - the signed manifest lists the file under its own name, with the file's SHA-384;
#   - with --name / --version, the manifest is that release (pass --version to refuse an older signed release).
# <file>.sha384 is not signed and is not used. Exits 0 only when every file verifies.
set -euo pipefail

die() { echo "verify-release: $*" >&2; exit 1; }

PUB="" PIN="" NAME="" VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --pub) PUB="${2:-}"; shift 2 ;;
        --fingerprint) PIN="${2:-}"; shift 2 ;;
        --name) NAME="${2:-}"; shift 2 ;;
        --version) VERSION="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option $1" ;;
        *) break ;;
    esac
done
command -v openssl >/dev/null || die "openssl is not installed"
[ -n "$PUB" ] && [ -f "$PUB" ] || die "--pub <public key or certificate PEM> is required"
[ -n "$PIN" ] || die "--fingerprint sha256:<hex> is required: the SHA-256 of the release's public key, as the release publishes it"
[ $# -gt 0 ] || die "no files to verify"

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
pubfile="$work/key.pub"
if grep -q "BEGIN CERTIFICATE" "$PUB"; then
    openssl x509 -in "$PUB" -pubkey -noout > "$pubfile" 2>/dev/null || die "cannot read the certificate $PUB"
else
    openssl pkey -pubin -in "$PUB" -out "$pubfile" 2>/dev/null || die "cannot read the public key $PUB"
fi
keyfp="$(openssl pkey -pubin -in "$pubfile" -outform DER 2>/dev/null | openssl dgst -sha256 -r | cut -d' ' -f1)"
want="$(printf '%s' "${PIN#sha256:}" | tr -d ':' | tr 'A-F' 'a-f')"
[ "$want" = "$keyfp" ] || die "the public key's fingerprint sha256:$keyfp is not the pinned $PIN"

fail=0
for f in "$@"; do
    name="$(basename "$f")"
    m="$f.manifest"
    if [ ! -f "$f" ] || [ ! -f "$m" ] || [ ! -f "$f.sig" ]; then
        echo "FAIL $name: missing the file, $name.manifest or $name.sig"; fail=1; continue
    fi
    if ! openssl dgst -sha384 -verify "$pubfile" -signature "$f.sig" "$m" >/dev/null 2>&1; then
        echo "FAIL $name: $name.sig is not the key sha256:$keyfp's signature of $name.manifest"; fail=1; continue
    fi
    # the manifest is now known to be the signer's
    if [ "$(head -1 "$m")" != "cresco-release-manifest 1" ]; then
        echo "FAIL $name: $name.manifest is not a release manifest this script knows"; fail=1; continue
    fi
    mname="$(awk '$1=="name" && NF==2 {print $2; exit}' "$m")"
    mver="$(awk '$1=="version" && NF==2 {print $2; exit}' "$m")"
    mkey="$(awk '$1=="signing-key" && NF==2 {print $2; exit}' "$m")"
    count="$(F="$name" awk '$1=="sha384" && NF==3 && $3==ENVIRON["F"]' "$m" | wc -l | tr -d ' ')"
    listed="$(F="$name" awk '$1=="sha384" && NF==3 && $3==ENVIRON["F"] {print $2}' "$m" | head -1)"
    got="$(openssl dgst -sha384 -r "$f" | cut -d' ' -f1)"
    if [ "$mkey" != "sha256:$keyfp" ]; then
        echo "FAIL $name: the manifest names the signing key $mkey, not sha256:$keyfp"; fail=1; continue
    fi
    if [ "$count" != "1" ]; then
        echo "FAIL $name: the signed manifest of $mname $mver does not list $name (it lists: $(awk '$1=="sha384"{printf "%s ", $3}' "$m"))"; fail=1; continue
    fi
    if [ "$listed" != "$got" ]; then
        echo "FAIL $name: SHA-384 $got is not the signed $listed"; fail=1; continue
    fi
    if [ -n "$NAME" ] && [ "$mname" != "$NAME" ]; then
        echo "FAIL $name: it is signed as part of $mname $mver, not $NAME"; fail=1; continue
    fi
    if [ -n "$VERSION" ] && [ "$mver" != "$VERSION" ]; then
        echo "FAIL $name: it is signed as $mname $mver, not version $VERSION"; fail=1; continue
    fi
    echo "OK   $name  ($mname $mver)  sha384:$got"
done
[ $fail -eq 0 ] || die "verification FAILED"
echo "all files verified (key sha256:$keyfp)"
