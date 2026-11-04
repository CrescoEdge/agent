#!/usr/bin/env bash
# Verify a signed agent release (from scripts/sign-release.sh).
#
#   scripts/verify-release.sh --pub <public key PEM or X.509 certificate PEM> --fingerprint sha256:<hex>
#                             (--build <id> | --manifest-sha384 <hex> | --not-before <YYYY-MM-DD[THH:MM:SSZ]>)
#                             [--name <name>] [--version <version>] file ...
#
# For each file it expects <file>.manifest and <file>.sig next to it, and accepts the file only when:
#   - the public key is the pinned one (--fingerprint, required: the SHA-256 of the public key the release publishes;
#     a public key file alone proves nothing, since whoever can replace it can sign);
#   - <file>.sig is that key's signature of <file>.manifest;
#   - the signed manifest lists the file under its own name, with the file's SHA-384;
#   - with --name / --version, the manifest is that release (pass --version to refuse an older signed release);
#   - it is the build you expect: --build (the id the release publishes) or --manifest-sha384 (the SHA-384 of the signed
#     manifest it publishes); use one of these. --not-before (the manifest was created then or later) is weak: any build
#     signed since that date passes; use it only when the release named neither. One of the three is required:
#     otherwise an older signed build of the same version, a SNAPSHOT above all, verifies as this one.
#   - a jar carries a valid known-vulnerability gate marker (the manifest's Cresco-Gate and Cresco-Gate-Files, the embedded
#     META-INF/cresco/sbom.cdx.json and embedded.sha384, every hash matching the jar's entries; checked with unzip and
#     openssl, never by running the jar), and a .cdx.json verified with it is the SBOM its gate passed.
# <file>.sha384 is not signed and is not used. Exits 0 only when every file verifies.
set -euo pipefail
export LC_ALL=C                     # byte-wise string comparison of the UTC timestamps

die() { echo "verify-release: $*" >&2; exit 1; }

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
    local re_name='^[A-Za-z0-9][A-Za-z0-9._+-]*$'
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

PUB="" PIN="" NAME="" VERSION="" BUILD="" MSHA="" NOTBEFORE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --pub) PUB="${2:-}"; shift 2 ;;
        --fingerprint) PIN="${2:-}"; shift 2 ;;
        --name) NAME="${2:-}"; shift 2 ;;
        --version) VERSION="${2:-}"; shift 2 ;;
        --build) BUILD="${2:-}"; shift 2 ;;
        --manifest-sha384) MSHA="$(printf '%s' "${2:-}" | sed 's/^sha384://' | tr 'A-F' 'a-f')"; shift 2 ;;
        --not-before) NOTBEFORE="${2:-}"; shift 2 ;;
        -h|--help) sed -n '2,23p' "$0"; exit 0 ;;
        --) shift; break ;;
        -*) die "unknown option $1" ;;
        *) break ;;
    esac
done
command -v openssl >/dev/null || die "openssl is not installed"
[ -n "$PUB" ] && [ -f "$PUB" ] || die "--pub <public key or certificate PEM> is required"
[ -n "$PIN" ] || die "--fingerprint sha256:<hex> is required: the SHA-256 of the release's public key, as the release publishes it"
[ -n "$BUILD" ] || [ -n "$MSHA" ] || [ -n "$NOTBEFORE" ] || die "say which build you expect: --build <id> or --manifest-sha384 <hex> (both published with the release), or at least --not-before <date>; otherwise an older signed build of the same version verifies"
if [ -n "$NOTBEFORE" ] && [ -z "$BUILD" ] && [ -z "$MSHA" ]; then
    echo "verify-release: WARNING: --not-before only refuses builds signed before $NOTBEFORE; any build signed since passes. Prefer --build or --manifest-sha384." >&2
fi
if [ -n "$NOTBEFORE" ]; then
    case "$NOTBEFORE" in
        [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) NOTBEFORE="${NOTBEFORE}T00:00:00Z" ;;
        [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) ;;
        *) die "--not-before takes YYYY-MM-DD or YYYY-MM-DDTHH:MM:SSZ (UTC), not $NOTBEFORE" ;;
    esac
fi
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
gated=" " sboms=""
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
    mbuild="$(awk '$1=="build" && NF==2 {print $2; exit}' "$m")"
    mcreated="$(awk '$1=="created" && NF==2 {print $2; exit}' "$m")"
    mhash="$(openssl dgst -sha384 -r "$m" | cut -d' ' -f1)"
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
    if [ -n "$BUILD" ] && [ "$mbuild" != "$BUILD" ]; then
        echo "FAIL $name: it is signed as build ${mbuild:-(none)}, not $BUILD"; fail=1; continue
    fi
    if [ -n "$MSHA" ] && [ "$mhash" != "$MSHA" ]; then
        echo "FAIL $name: its signed manifest is sha384:$mhash, not the published $MSHA"; fail=1; continue
    fi
    if [ -n "$NOTBEFORE" ] && { [ -z "$mcreated" ] || [[ "$mcreated" < "$NOTBEFORE" ]]; }; then
        echo "FAIL $name: it was signed ${mcreated:-at an unknown time}, before $NOTBEFORE"; fail=1; continue
    fi
    case "$name" in *.jar)
        r="$(gate_check "$f" "$work")"
        case "$r" in "OK -") ;; "OK "*) gated="$gated${r#OK } " ;; *) echo "FAIL $name: $r"; fail=1; continue ;; esac ;;
    *.cdx.json) sboms="$sboms$got $name
" ;;
    esac
    echo "OK   $name  ($mname $mver, build ${mbuild:-?}, signed $mcreated)  sha384:$got"
done
if [ "$gated" != " " ]; then
    while IFS=' ' read -r s n; do
        [ -n "$s" ] || continue
        case "$gated" in *" $s "*) ;; *) echo "FAIL $n: it is not the SBOM the jar's known-vulnerability gate passed"; fail=1 ;; esac
    done <<< "$sboms"
fi
[ $fail -eq 0 ] || die "verification FAILED"
echo "all files verified (key sha256:$keyfp)"
