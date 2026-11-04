#!/usr/bin/env bash
# Sign an agent release (the agent jar and its CycloneDX SBOM) with an operator-held key.
#
#   scripts/sign-release.sh --key <private key PEM> [--passin <src>] [--name <name>] [--version <version>]
#                           [--out <dir>] [file ...]
#
# The files signed together are one release. The script writes a release manifest that names the release, its version
# and the signing key, and lists the SHA-384 of every file; it signs the manifest, not the bare bytes, so the signature
# covers each file's name and the release's version. Next to each file (or in --out) it writes:
#   <file>.manifest  the release manifest (the same for every file of the release)
#   <file>.sig       the detached signature of the manifest: SHA-384 with the key (ECDSA or RSA, DER)
#   <file>.sha384    the file's SHA-384 ("<hex>  <name>", for sha384sum -c / shasum -a 384 -c); a convenience, not signed
# and prints the SHA-256 fingerprint of the signing public key, to publish with the release. scripts/verify-release.sh
# accepts a file only when the signed manifest lists it under its own name with its SHA-384, so an older signed release
# cannot be passed off under a newer release's name.
#
# Name and version: --name / --version, else from the files named <name>-<version>.jar or <name>-<version>.cdx.json
# (target/agent-1.3.jar is agent 1.3). Files whose names disagree with each other or with --name / --version are refused.
#
# The key: EC P-384 (or P-521) or RSA-3072+, owner-only (mode 0600 or 0400, no ACL), owned by you, and never inside this
# repository (the script refuses a key in the work tree). Make one once, offline, and keep it off the build host when
# you can:
#   umask 077; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes-256-cbc -out release-signing.key
#   openssl pkey -in release-signing.key -pubout -out release-signing.pub      # publish this, and its fingerprint
# --passin passes openssl's passphrase source for an encrypted key (e.g. env:RELEASE_KEY_PASS or file:/path); without
# it openssl asks on the terminal.
#
# Needs bash 3.2+, openssl 1.1.1+ (or LibreSSL) and POSIX ls/awk: macOS, BSD and GNU/Linux (the DGX) alike. A release
# does not need GitHub Actions.
set -euo pipefail

die() { echo "sign-release: $*" >&2; exit 1; }

KEY="" PASSIN="" OUT="" NAME="" VERSION=""
while [ $# -gt 0 ]; do
    case "$1" in
        --key) KEY="${2:-}"; shift 2 ;;
        --passin) PASSIN="${2:-}"; shift 2 ;;
        --out) OUT="${2:-}"; shift 2 ;;
        --name) NAME="${2:-}"; shift 2 ;;
        --version) VERSION="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option $1" ;;
        *) break ;;
    esac
done
command -v openssl >/dev/null || die "openssl is not installed"
[ -n "$KEY" ] || die "--key <private key PEM> is required"
[ -f "$KEY" ] && [ ! -L "$KEY" ] || die "the key $KEY is not a regular file"

# owner-only key. ls -ln prints the same mode string on macOS, BSD and GNU/Linux (stat's options differ between them);
# a trailing "." (SELinux label) or "@" (extended attributes) is harmless, a "+" is an ACL that may grant others access.
listing="$(LC_ALL=C ls -ln "$KEY" 2>/dev/null)" || die "cannot list the key $KEY"
mode="$(printf '%s\n' "$listing" | awk 'NR==1{print $1}')"
owner="$(printf '%s\n' "$listing" | awk 'NR==1{print $3}')"
case "$mode" in
    -rw-------|-r--------|-rw-------.|-r--------.|-rw-------@|-r--------@) ;;
    *+) die "the key $KEY has an access control list ($mode); remove it (chmod -N on macOS, setfacl -b on Linux) and make it 0600" ;;
    *) die "the key $KEY has mode $mode; make it owner-only, 0600 or 0400 (chmod 600)" ;;
esac
[ "$owner" = "$(id -u)" ] || die "the key $KEY belongs to uid $owner, not to you (uid $(id -u))"

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

work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
pubfile="$work/key.pub"
openssl pkey -in "$KEY" ${passin[@]+"${passin[@]}"} -pubout -out "$pubfile" 2>/dev/null || die "cannot derive the public key"
keyfp="$(openssl pkey -pubin -in "$pubfile" -outform DER 2>/dev/null | openssl dgst -sha256 -r | cut -d' ' -f1)"

if [ $# -eq 0 ]; then
    shopt -s nullglob
    set -- "$here"/target/agent-*.jar "$here"/target/agent-*.cdx.json
    shopt -u nullglob
    [ $# -gt 0 ] || die "no files given and no target/agent-*.jar or target/agent-*.cdx.json; run mvn package first"
fi

# the release: its name and version, and every file's name (one line each in the manifest, so no blanks or newlines)
safe='^[A-Za-z0-9][A-Za-z0-9._+-]*$'
release='^([A-Za-z][A-Za-z0-9_.]*(-[A-Za-z][A-Za-z0-9_.]*)*)-([0-9][A-Za-z0-9._+-]*)\.(jar|cdx\.json)$'
seen=" "
for f in "$@"; do
    [ -f "$f" ] || die "$f does not exist"
    b="$(basename "$f")"
    [[ "$b" =~ $safe ]] || die "the file name $b has characters a release manifest does not allow (use letters, digits and ._+-)"
    case "$seen" in *" $b "*) die "two files are named $b; a release names each file once" ;; esac
    seen="$seen$b "
    if [[ "$b" =~ $release ]]; then
        n="${BASH_REMATCH[1]}" v="${BASH_REMATCH[3]}"
        [ -z "$NAME" ] && NAME="$n"
        [ -z "$VERSION" ] && VERSION="$v"
        [ "$n" = "$NAME" ] && [ "$v" = "$VERSION" ] || die "$b is $n $v, not $NAME $VERSION; sign each release separately (or rename the file)"
    fi
done
[ -n "$NAME" ] && [ -n "$VERSION" ] || die "cannot tell the release name and version from the file names; give --name and --version"
[[ "$NAME" =~ $safe ]] && [[ "$VERSION" =~ $safe ]] || die "the release name and version may use letters, digits and ._+- only"

manifest="$work/release.manifest"
{
    echo "cresco-release-manifest 1"
    echo "name $NAME"
    echo "version $VERSION"
    echo "signing-key sha256:$keyfp"
    echo "created $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    for f in "$@"; do
        echo "sha384 $(openssl dgst -sha384 -r "$f" | cut -d' ' -f1) $(basename "$f")"
    done
} > "$manifest"
openssl dgst -sha384 -sign "$KEY" ${passin[@]+"${passin[@]}"} -out "$work/release.sig" "$manifest" || die "signing the release manifest failed"
openssl dgst -sha384 -verify "$pubfile" -signature "$work/release.sig" "$manifest" >/dev/null || die "the new signature does not verify"

for f in "$@"; do
    dir="${OUT:-$(dirname "$f")}"
    mkdir -p "$dir"
    b="$(basename "$f")"
    sum="$(awk -v b="$b" '$1=="sha384" && $3==b {print $2}' "$manifest")"
    printf '%s  %s\n' "$sum" "$b" > "$dir/$b.sha384"
    cp "$manifest" "$dir/$b.manifest"
    cp "$work/release.sig" "$dir/$b.sig"
    echo "signed $b  ($NAME $VERSION)  sha384:$sum"
done
echo "signing key: $kind, public key fingerprint sha256:$keyfp"
echo "verify with: scripts/verify-release.sh --pub release-signing.pub --fingerprint sha256:$keyfp --version $VERSION <files>"
