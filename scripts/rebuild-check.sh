#!/usr/bin/env bash
# Is this agent jar the build of this checkout?
#
#   scripts/rebuild-check.sh <agent jar> [--rebuild]
#
# In a checkout of the commit a release names (git checkout 1.3.<n>), it checks that
#   1. the jar carries this checkout's bundles.lock (META-INF/cresco/bundles.lock), byte for byte;
#   2. every embedded Cresco bundle in the jar has the SHA-256 the lock names, and every bundle the lock names is in it;
#   3. with --rebuild: the checkout, built here (mvn -B -o package -DskipTests, offline, with the jar's release name),
#      gives a jar with the same entries and the same bytes in each -- except the entries that record the build itself
#      (the manifest, with the build time; Maven's pom.properties; the SBOM and the gate marker, which name the build).
#      The differing entries are listed. A rebuild needs the same JDK major version and a populated local Maven
#      repository.
# Exits 0 only when every check passes. Uses unzip, cmp and sha256sum or shasum; it never runs code from the jar.
set -euo pipefail

die() { echo "rebuild-check: $*" >&2; exit 1; }
sha256_stdin() { if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi; }

[ "$#" -ge 1 ] || die "usage: scripts/rebuild-check.sh <agent jar> [--rebuild]"
JAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; REBUILD="${2:-}"
[ -f "$JAR" ] || die "$1 is not a file"
cd "$(cd "$(dirname "$0")/.." && pwd)"
[ -f bundles.lock ] || die "this checkout has no bundles.lock"
bad=0

unzip -p "$JAR" META-INF/cresco/bundles.lock 2>/dev/null | cmp -s - bundles.lock \
    || { echo "rebuild-check: the jar's bundles.lock is not this checkout's (another commit, or a jar from before the lock)" >&2; bad=1; }

while read -r file bsn ver want origin; do
    case "$file" in ''|'#'*) continue ;; esac
    got="$(unzip -p "$JAR" "$file" 2>/dev/null | sha256_stdin)"
    if [ "$got" != "$want" ]; then
        echo "rebuild-check: $file in the jar is not the bundle the lock names ($ver, $origin): SHA-256 $got, the lock says $want" >&2; bad=1
    fi
done < bundles.lock
[ "$bad" = 0 ] && echo "rebuild-check: the jar embeds the $(grep -vc '^#' bundles.lock) Cresco bundles bundles.lock names"

if [ "$REBUILD" = "--rebuild" ]; then
    rel="$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Cresco-Release: //p' | head -1)"
    mvn -B -o -q package -DskipTests "-Dcresco.release=${rel:-unreleased}" || die "the checkout does not build"
    mine="target/agent-1.3-SNAPSHOT.jar"
    # name and CRC-32 of every entry, but for the entries that record the build itself
    list() { unzip -v "$1" | awk 'NR > 3 && $NF !~ /\/$/ && NF >= 8 { print $NF, $(NF-1) }' \
        | grep -v -e '^META-INF/MANIFEST.MF ' -e '^META-INF/maven/.*/pom.properties ' -e '^META-INF/cresco/sbom.cdx.json ' \
                  -e '^META-INF/cresco/embedded.sha384 ' | LC_ALL=C sort; }
    if diff <(list "$JAR") <(list "$mine") > target/rebuild-check.diff; then
        echo "rebuild-check: a rebuild of this checkout has the same $(list "$mine" | wc -l | tr -d ' ') entries, byte for byte (but for the entries that record the build)"
    else
        echo "rebuild-check: a rebuild of this checkout differs from the jar in $(grep -c '^[<>]' target/rebuild-check.diff) entr(ies) (< the jar, > the rebuild):" >&2
        head -20 target/rebuild-check.diff >&2
        bad=1
    fi
fi
[ "$bad" = 0 ] || exit 1
