#!/usr/bin/env bash
# Sign an agent release (the agent jar and its CycloneDX SBOM) with an operator-held key.
#
#   scripts/sign-release.sh --key <private key PEM> [--passin <src>] [--name <name>] [--version <version>]
#                           [--build <id>] [--out <dir>] [file ...]
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
# Build: the manifest also names this exact build, so an older signed build of the same (SNAPSHOT) version cannot pass
# as this one: --build, else the jar's Implementation-Version (version, git revision and build time, written by the
# build), else the signing time and this repository's git revision. The script prints the build id and the manifest's
# SHA-384; publish both with the fingerprint (scripts/verify-release.sh --build / --manifest-sha384 check them).
#
# The key: EC P-384 (or P-521) or RSA-3072+, owner-only (mode 0600 or 0400, no ACL), owned by you, and never inside this
# repository (the script refuses a key in the work tree). Make one once, offline, and keep it off the build host when
# you can:
#   umask 077; openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-384 -aes-256-cbc -out release-signing.key
#   openssl pkey -in release-signing.key -pubout -out release-signing.pub      # publish this, and its fingerprint
# --passin passes openssl's passphrase source for an encrypted key (e.g. env:RELEASE_KEY_PASS or file:/path); without
# it openssl asks on the terminal.
#
# A jar is signed only when it carries a valid known-vulnerability gate marker (written by the build's
# KnownVulnerabilityGate when it passed): the manifest's Cresco-Gate (passed sha384:<SBOM>) and Cresco-Gate-Files, the
# embedded SBOM META-INF/cresco/sbom.cdx.json and META-INF/cresco/embedded.sha384, every hash matching the jar's own
# entries; and a .cdx.json signed with it must be that SBOM. So a jar assembled without the gate (mvn compile
# assembly:single, -Dmaven.antrun.skip=true with an older pom) is refused.
#
# Needs bash 3.2+, openssl 1.1.1+ (or LibreSSL), unzip and POSIX ls/awk: macOS, BSD and GNU/Linux (the DGX) alike. A
# release does not need GitHub Actions.
set -euo pipefail

die() { echo "sign-release: $*" >&2; exit 1; }

# gate_check <jar> <work dir>: prints "OK <sha384 of the SBOM the gate passed>" when the jar carries a valid
# known-vulnerability gate marker, "OK -" when the file is not a zip archive (not a jar at all), else why not. The marker (written by KnownVulnerabilityGate only when it passed): the
# manifest's Cresco-Gate (passed sha384:<SBOM>) and Cresco-Gate-Files (sha384:<list>), the embedded SBOM
# META-INF/cresco/sbom.cdx.json and the list META-INF/cresco/embedded.sha384 of every embedded jar's SHA-384. Every
# hash is checked against the jar's own entries. Uses unzip and openssl only, never code from the jar.
gate_check() {
    local jar="$1" tmp="$2" mf marker files sbom list line sum entry actual n=0
    # a file that is not a zip archive is not a jar anything can run: it is signed as data, like any other file
    if [ "$(LC_ALL=C head -c 2 "$jar" 2>/dev/null)" != "PK" ]; then echo "OK -"; return 0; fi
    local re_gate='^passed sha384:([0-9a-f]{96})$' re_files='^sha384:([0-9a-f]{96})$' re_sum='^[0-9a-f]{96}$'
    local re_name='^(crypto/)?[A-Za-z0-9][A-Za-z0-9._+-]*$'
    command -v unzip >/dev/null || { echo "unzip is not installed, so its known-vulnerability gate marker cannot be checked"; return 0; }
    mf="$( { unzip -p "$jar" META-INF/MANIFEST.MF 2>/dev/null || true; } | tr -d '\r' \
        | awk 'substr($0, 1, 1) == " " { cur = cur substr($0, 2); next } { if (NR > 1) print cur; cur = $0 } END { print cur }')"
    marker="$(printf '%s\n' "$mf" | sed -n 's/^Cresco-Gate: //p' | head -1)"
    files="$(printf '%s\n' "$mf" | sed -n 's/^Cresco-Gate-Files: //p' | head -1)"
    if [[ ! "$marker" =~ $re_gate ]]; then
        echo "it carries no known-vulnerability gate marker (Cresco-Gate in its manifest): build it with mvn package, whose known-vulnerability gate writes it; a jar assembled without the gate is not a release"
        return 0
    fi
    sbom="${BASH_REMATCH[1]}"
    [[ "$files" =~ $re_files ]] || { echo "its manifest has Cresco-Gate but no valid Cresco-Gate-Files"; return 0; }
    list="${BASH_REMATCH[1]}"
    actual="$( { unzip -p "$jar" META-INF/cresco/sbom.cdx.json 2>/dev/null || true; } | openssl dgst -sha384 -r | cut -d' ' -f1)"
    [ "$actual" = "$sbom" ] || { echo "its embedded SBOM (META-INF/cresco/sbom.cdx.json) is not the one its gate marker names"; return 0; }
    { unzip -p "$jar" META-INF/cresco/embedded.sha384 2>/dev/null || true; } > "$tmp/embedded.sha384"
    actual="$(openssl dgst -sha384 -r "$tmp/embedded.sha384" | cut -d' ' -f1)"
    [ "$actual" = "$list" ] || { echo "its META-INF/cresco/embedded.sha384 is not the list its gate marker names"; return 0; }
    while IFS= read -r line || [ -n "$line" ]; do
        [ -n "$line" ] || continue
        sum="${line%%  *}"
        entry="${line#*  }"
        if [[ ! "$sum" =~ $re_sum ]] || [[ ! "$entry" =~ $re_name ]]; then
            echo "its META-INF/cresco/embedded.sha384 has a malformed line"; return 0
        fi
        actual="$( { unzip -p "$jar" "$entry" 2>/dev/null || true; } | openssl dgst -sha384 -r | cut -d' ' -f1)"
        [ "$actual" = "$sum" ] || { echo "its embedded $entry is not the one the gate checked (SHA-384 $actual, gated $sum)"; return 0; }
        n=$((n + 1))
    done < "$tmp/embedded.sha384"
    [ "$n" -gt 0 ] || { echo "its gate marker lists no embedded jar"; return 0; }
    echo "OK $sbom"
}

KEY="" PASSIN="" OUT="" NAME="" VERSION="" BUILD=""
while [ $# -gt 0 ]; do
    case "$1" in
        --key) KEY="${2:-}"; shift 2 ;;
        --passin) PASSIN="${2:-}"; shift 2 ;;
        --out) OUT="${2:-}"; shift 2 ;;
        --name) NAME="${2:-}"; shift 2 ;;
        --version) VERSION="${2:-}"; shift 2 ;;
        --build) BUILD="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,44p' "$0"; exit 0 ;;
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

# every jar: a valid known-vulnerability gate marker; every SBOM signed with a jar: the one its gate passed
gated=" "
for f in "$@"; do
    case "$f" in *.jar)
        r="$(gate_check "$f" "$work")"
        case "$r" in "OK -") ;; "OK "*) gated="$gated${r#OK } " ;; *) die "$(basename "$f"): $r" ;; esac ;;
    esac
done
if [ "$gated" != " " ]; then
    for f in "$@"; do
        case "$f" in *.cdx.json)
            s="$(openssl dgst -sha384 -r "$f" | cut -d' ' -f1)"
            case "$gated" in *" $s "*) ;; *) die "$(basename "$f") is not the SBOM the jar's known-vulnerability gate passed (META-INF/cresco/sbom.cdx.json)" ;; esac ;;
        esac
    done
fi
[[ "$NAME" =~ $safe ]] && [[ "$VERSION" =~ $safe ]] || die "the release name and version may use letters, digits and ._+- only"

created="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ -z "$BUILD" ]; then
    for f in "$@"; do
        case "$f" in *.jar)
            if command -v unzip >/dev/null; then
                BUILD="$( { unzip -p "$f" META-INF/MANIFEST.MF 2>/dev/null || true; } | tr -d '\r' | sed -n 's/^Implementation-Version: *//p' | head -1)"
            fi
            [ -n "$BUILD" ] && break ;;
        esac
    done
fi
if [ -z "$BUILD" ]; then
    rev="$(git -C "$here" rev-parse --short=12 HEAD 2>/dev/null || true)"
    [ -n "$rev" ] && [ -n "$(git -C "$here" status --porcelain 2>/dev/null | head -1)" ] && rev="$rev-dirty"
    BUILD="$(printf '%s' "$created" | tr -d ':-')-${rev:-norev}"
fi
[[ "$BUILD" =~ $safe ]] || die "the build id may use letters, digits and ._+- only: $BUILD"

manifest="$work/release.manifest"
{
    echo "cresco-release-manifest 1"
    echo "name $NAME"
    echo "version $VERSION"
    echo "build $BUILD"
    echo "signing-key sha256:$keyfp"
    echo "created $created"
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
msha="$(openssl dgst -sha384 -r "$manifest" | cut -d' ' -f1)"
echo "signing key: $kind, public key fingerprint sha256:$keyfp"
echo "build: $BUILD  (manifest sha384:$msha, created $created)"
echo "verify with: scripts/verify-release.sh --pub release-signing.pub --fingerprint sha256:$keyfp --build $BUILD <files>"
