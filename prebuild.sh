#!/usr/bin/env bash
#
# The Cresco bundles the agent embeds (src/main/resources/<name>.jar, version-less names: HostApplication and
# StaticPluginLoader load them by those names) and bundles.lock, the list of exactly which ones: file, Bundle-SymbolicName,
# Bundle-Version, SHA-256 and where each came from. The jars are committed; the lock says what they are. A build embeds
# what the lock names, byte for byte, or it fails (EmbeddedBundleCheck, in mvn package), so a release can be named and
# built again, and nothing depends on what a repository serves on the day of the build.
#
#   prebuild.sh                    verify: every embedded Cresco bundle is the one bundles.lock names (no network)
#   prebuild.sh --update [name..]  fetch the latest snapshot of the named bundles from Maven Central (default: every
#                                  bundle the lock says came from there), check it against Central's checksum, put it
#                                  in place and rewrite its line of the lock
#   prebuild.sh --lock [name..]    record the named jars (default: all) as they are, origin "committed": after putting a
#                                  bundle you built yourself in place (mvn clean package bundle:bundle in its repository)
#
# A bundle from Central is recorded as central:io.cresco:<artifact>:<snapshot>@<source commit>, the commit being the one
# its manifest names (Cresco-Commit, first 12 characters; absent for a bundle built before 2026-11-11).
#
# --update downloads with curl into a temporary directory. It never reads ~/.m2: a bundle installed there by a local
# build would be taken for the published one (it was, by the script this replaces). Review the diff of bundles.lock
# before you commit: it is the statement of what the next release contains.
#
# Needs bash 3.2+, unzip, curl (for --update) and sha256sum or shasum: macOS and GNU/Linux alike.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$HERE/src/main/resources"
LOCK="$HERE/bundles.lock"
REPO="${CRESCO_SNAPSHOT_REPO:-https://central.sonatype.com/repository/maven-snapshots}"
GROUP_PATH="io/cresco"

die() { echo "prebuild: $*" >&2; exit 1; }

CLEANUP=""                                      # the temporary directory of --update, removed when the script ends
trap '[ -z "$CLEANUP" ] || rm -rf "$CLEANUP"' EXIT

sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
sha1() { if command -v sha1sum >/dev/null 2>&1; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi; }

# manifest_value <jar> <header>: the header's value (continuation lines joined, directives after ';' dropped), or empty
manifest_value() {
    { unzip -p "$1" META-INF/MANIFEST.MF 2>/dev/null || true; } | tr -d '\r' \
        | awk 'substr($0, 1, 1) == " " { cur = cur substr($0, 2); next } { if (NR > 1) print cur; cur = $0 } END { print cur }' \
        | sed -n "s/^$2: //p" | head -1 | sed 's/;.*//'
}

# every embedded Cresco bundle's file name (Bundle-SymbolicName io.cresco.*), sorted
embedded() {
    local f
    for f in "$RES"/*.jar; do
        [ -f "$f" ] || continue
        case "$(manifest_value "$f" Bundle-SymbolicName)" in io.cresco.*) basename "$f" ;; esac
    done | LC_ALL=C sort
}

# lock_field <file name> <column>: 2 symbolic name, 3 bundle version, 4 sha256, 5 origin
lock_field() { [ -f "$LOCK" ] && awk -v f="$1" -v c="$2" '$1 == f { print $c; exit }' "$LOCK" || true; }

# set_line <file name> <origin>: write the jar's line into the lock (replacing its old one), keeping the lock sorted
set_line() {
    local name="$1" origin="$2" jar="$RES/$1" bsn ver tmp
    bsn="$(manifest_value "$jar" Bundle-SymbolicName)"; ver="$(manifest_value "$jar" Bundle-Version)"
    [ -n "$bsn" ] || die "$name has no Bundle-SymbolicName: not an OSGi bundle (build it with mvn clean package bundle:bundle)"
    tmp="$(mktemp "$HERE/.bundles.lock.XXXXXX")"
    {
        echo "# The Cresco bundles this agent embeds (src/main/resources/<file>): what a build contains, byte for byte."
        echo "# Written by prebuild.sh (--update, --lock); checked by prebuild.sh and by the build (EmbeddedBundleCheck)."
        echo "# file  Bundle-SymbolicName  Bundle-Version  SHA-256  origin"
        { [ -f "$LOCK" ] && grep -v '^#' "$LOCK" | awk -v f="$name" 'NF >= 5 && $1 != f' || true
          echo "$name $bsn ${ver:--} $(sha256 "$jar") $origin"; } | LC_ALL=C sort
    } > "$tmp"
    mv "$tmp" "$LOCK"
}

verify() {
    local bad=0 name want got seen=""
    [ -f "$LOCK" ] || die "bundles.lock is missing: prebuild.sh --lock writes it from the jars in $RES"
    for name in $(embedded); do
        seen="$seen $name "
        want="$(lock_field "$name" 4)"
        if [ -z "$want" ]; then
            echo "prebuild: $name is embedded and not in bundles.lock (prebuild.sh --lock $name records it)" >&2; bad=1; continue
        fi
        got="$(sha256 "$RES/$name")"
        if [ "$got" != "$want" ]; then
            echo "prebuild: $name is not the bundle bundles.lock names: SHA-256 $got, the lock says $want" \
                 "($(lock_field "$name" 3), $(lock_field "$name" 5))" >&2; bad=1
        fi
    done
    while read -r name _; do
        case "$name" in ''|'#'*) continue ;; esac
        case "$seen" in *" $name "*) ;; *) echo "prebuild: bundles.lock names $name, which is not in $RES" >&2; bad=1 ;; esac
    done < "$LOCK"
    [ "$bad" = 0 ] || die "the embedded bundles are not what bundles.lock says (see above)"
    echo "prebuild: $(embedded | wc -l | tr -d ' ') embedded Cresco bundles are the ones bundles.lock names"
}

update() {
    local name art meta ts bn ver url tmp want commit origin
    command -v curl >/dev/null 2>&1 || die "--update needs curl"
    tmp="$(mktemp -d "${TMPDIR:-/tmp}/cresco-prebuild.XXXXXX")"
    CLEANUP="$tmp"
    if [ "$#" = 0 ]; then
        set -- $(grep -v '^#' "$LOCK" 2>/dev/null | awk '$5 ~ /^central:/ { print $1 }')
        [ "$#" -gt 0 ] || die "bundles.lock names no bundle from Central: name the bundles to update"
    fi
    for name in "$@"; do
        name="${name%.jar}.jar"; art="${name%.jar}"
        meta="$(curl -fsS -A cresco-prebuild "$REPO/$GROUP_PATH/$art/1.3-SNAPSHOT/maven-metadata.xml")" \
            || die "$art: no snapshot metadata at $REPO"
        ts="$(printf '%s' "$meta" | sed -n 's/.*<timestamp>\([^<]*\)<.*/\1/p' | head -1)"
        bn="$(printf '%s' "$meta" | sed -n 's/.*<buildNumber>\([^<]*\)<.*/\1/p' | head -1)"
        [ -n "$ts" ] && [ -n "$bn" ] || die "$art: the snapshot metadata names no build"
        ver="1.3-$ts-$bn"; url="$REPO/$GROUP_PATH/$art/1.3-SNAPSHOT/$art-$ver.jar"
        curl -fsS -A cresco-prebuild -o "$tmp/$name" "$url" || die "$art: download of $ver failed"
        want="$(curl -fsS -A cresco-prebuild "$url.sha1" | cut -c1-40)" || die "$art: no checksum for $ver"
        [ "$(sha1 "$tmp/$name")" = "$want" ] || die "$art: the download does not match Central's checksum for $ver"
        mv "$tmp/$name" "$RES/$name"
        # the source commit the bundle says it was built from (its manifest's Cresco-Commit, written by its CI build)
        commit="$(manifest_value "$RES/$name" Cresco-Commit)"
        case "$commit" in ''|unknown*|*[!0-9a-f]*) origin="central:io.cresco:$art:$ver" ;;
                          *) origin="central:io.cresco:$art:$ver@$(printf '%s' "$commit" | cut -c1-12)" ;; esac
        set_line "$name" "$origin"
        echo "prebuild: $name is now $origin ($(lock_field "$name" 3))"
    done
}

lock() {
    local name origin
    [ "$#" -gt 0 ] || set -- $(embedded)
    for name in "$@"; do
        name="${name%.jar}.jar"
        [ -f "$RES/$name" ] || die "$name is not in $RES"
        # a jar that is still the one the lock names keeps its origin; any other is what was committed
        origin="committed"
        [ "$(lock_field "$name" 4)" = "$(sha256 "$RES/$name")" ] && origin="$(lock_field "$name" 5)"
        set_line "$name" "$origin"
    done
    echo "prebuild: bundles.lock records $# bundle(s) as they are"
}

case "${1:-}" in
    "")        verify ;;
    --update)  shift; update "$@"; verify ;;
    --lock)    shift; lock "$@"; verify ;;
    -h|--help) sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//' ;;
    *)         die "unknown option $1 (see --help)" ;;
esac
