#!/usr/bin/env bash
# Sign an agent release (the agent jar and its CycloneDX SBOM) with an operator-held key.
#
#   scripts/sign-release.sh --key <private key PEM> [--passin <openssl passin>] [--out <dir>] [file ...]
#
# For each file (default: target/agent-<version>.jar and target/agent-<version>.cdx.json) it writes, next to the file
# or in --out:
#   <file>.sha384   the SHA-384 checksum ("<hex>  <name>", as sha384sum / shasum -a 384 -c read it)
#   <file>.sig      a detached signature: SHA-384 with the key (ECDSA or RSA, DER), checked by scripts/verify-release.sh
# and prints the SHA-256 fingerprint of the signing public key, to publish with the release.
#
# The key: EC P-384 (or P-521) or RSA-3072+, mode 0600 or 0400, and never inside this repository (the script refuses
# a key in the work tree). Make one once, offline, and keep it off the build host when you can:
#   umask 077; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes-256-cbc -out release-signing.key
#   openssl pkey -in release-signing.key -pubout -out release-signing.pub      # publish this, and its fingerprint
# --passin passes openssl's passphrase source for an encrypted key (e.g. env:RELEASE_KEY_PASS or file:/path); without
# it openssl asks on the terminal.
#
# Needs bash and openssl 1.1.1+ (or LibreSSL). Works on any build host: a release does not need GitHub Actions.
set -euo pipefail

die() { echo "sign-release: $*" >&2; exit 1; }

KEY="" PASSIN="" OUT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --key) KEY="${2:-}"; shift 2 ;;
        --passin) PASSIN="${2:-}"; shift 2 ;;
        --out) OUT="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option $1" ;;
        *) break ;;
    esac
done
command -v openssl >/dev/null || die "openssl is not installed"
[ -n "$KEY" ] || die "--key <private key PEM> is required"
[ -f "$KEY" ] && [ ! -L "$KEY" ] || die "the key $KEY is not a regular file"

# owner-only key
mode=$(stat -f %Lp "$KEY" 2>/dev/null || stat -c %a "$KEY")
case "$mode" in 600|400) ;; *) die "the key $KEY has mode $mode; make it 0600 or 0400 (chmod 600)" ;; esac

# never a key inside the repository
here="$(cd "$(dirname "$0")/.." && pwd -P)"
repo="$(git -C "$here" rev-parse --show-toplevel 2>/dev/null || echo "$here")"
repo="$(cd "$repo" && pwd -P)"
keyreal="$(cd "$(dirname "$KEY")" && pwd -P)/$(basename "$KEY")"
case "$keyreal" in "$repo"/*) die "the key $KEY is inside the repository $repo; keep signing keys out of the source tree" ;; esac

passin=()
[ -n "$PASSIN" ] && passin=(-passin "$PASSIN")

# policy strength: EC P-384/P-521 or RSA-3072+
text="$(openssl pkey -in "$KEY" ${passin[@]+"${passin[@]}"} -noout -text 2>/dev/null)" || die "cannot read the key $KEY (wrong passphrase, or not a PEM private key)"
bits="$(printf '%s\n' "$text" | head -1 | sed -n 's/.*(\([0-9]*\) bit.*/\1/p')"
if printf '%s' "$text" | grep -q "modulus:"; then
    [ "${bits:-0}" -ge 3072 ] || die "the key is RSA-$bits; at least RSA-3072 is required"
    kind="RSA-$bits"
elif printf '%s' "$text" | grep -Eq "secp384r1|secp521r1|P-384|P-521"; then
    kind="EC-$bits"
else
    die "the key must be EC P-384 / P-521 or RSA-3072+"
fi

pubfile="$(mktemp)"; trap 'rm -f "$pubfile"' EXIT
openssl pkey -in "$KEY" ${passin[@]+"${passin[@]}"} -pubout -out "$pubfile" 2>/dev/null || die "cannot derive the public key"
keyfp="$(openssl pkey -pubin -in "$pubfile" -outform DER 2>/dev/null | openssl dgst -sha256 -r | cut -d' ' -f1)"

if [ $# -eq 0 ]; then
    shopt -s nullglob
    set -- "$here"/target/agent-*.jar "$here"/target/agent-*.cdx.json
    shopt -u nullglob
    [ $# -gt 0 ] || die "no files given and no target/agent-*.jar or target/agent-*.cdx.json; run mvn package first"
fi

for f in "$@"; do
    [ -f "$f" ] || die "$f does not exist"
    dir="${OUT:-$(dirname "$f")}"
    mkdir -p "$dir"
    name="$(basename "$f")"
    sum="$(openssl dgst -sha384 -r "$f" | cut -d' ' -f1)"
    printf '%s  %s\n' "$sum" "$name" > "$dir/$name.sha384"
    openssl dgst -sha384 -sign "$KEY" ${passin[@]+"${passin[@]}"} -out "$dir/$name.sig" "$f" || die "signing $f failed"
    openssl dgst -sha384 -verify "$pubfile" -signature "$dir/$name.sig" "$f" >/dev/null || die "the new signature of $f does not verify"
    echo "signed $name  sha384:$sum"
done
echo "signing key: $kind, public key fingerprint sha256:$keyfp"
echo "verify with: scripts/verify-release.sh --pub release-signing.pub --fingerprint sha256:$keyfp <files>"
