#!/usr/bin/env bash
# Publish a release of the agent that can be named and is never replaced.
#
#   scripts/release.sh [--build] [--alias] [--dry-run]
#
# The release is 1.3.<n>, n being the number of commits on main up to the released one: every commit of main has one
# name, later commits have higher ones, and nobody chooses it. It is a GitHub release of its own, with its own tag on
# that commit, holding
#   agent-1.3.<n>.jar          the agent (its manifest says Cresco-Release: 1.3.<n>; java -jar ... --version prints it)
#   agent-1.3.<n>.jar.sha256   its SHA-256
#   bundles.lock               the embedded Cresco bundles, byte for byte (the jar carries the same file)
#   agent-1.3.<n>.cdx.json     its CycloneDX SBOM, when the build wrote one
# An existing release is never replaced: run again for the same commit, the script says so and changes nothing. Until
# now the only release was the asset agent-1.3-SNAPSHOT.jar under the tag 1.3-SNAPSHOT, replaced in place on every push:
# yesterday's release could not be named, fetched or compared with today's.
#
#   --build    verify the embedded bundles (prebuild.sh) and build the jar for this commit first (mvn -B package)
#   --alias    also put the jar under the moving release 1.3-SNAPSHOT (what "the latest 1.3" downloads); the CI workflow
#              does that itself
#   --dry-run  everything but the publishing
#
# Without --build the jar in target/ must be the one built for this commit and this release name (the CI workflow builds
# it with -Dcresco.release=1.3.<n>); a jar built for anything else is refused. The work tree must be clean and the
# commit must be on origin/main: a release is a commit everybody can see.
#
# Needs git, the GitHub CLI (gh, authenticated; GH_TOKEN in CI), unzip and sha256sum or shasum. Signing a release with
# an operator-held key is scripts/sign-release.sh; verifying one is scripts/verify-release.sh and scripts/rebuild-check.sh.
set -euo pipefail

die() { echo "release: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

BUILD=0; ALIAS=0; DRY=0
for a in "$@"; do
    case "$a" in
        --build) BUILD=1 ;; --alias) ALIAS=1 ;; --dry-run) DRY=1 ;;
        -h|--help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option $a (see --help)" ;;
    esac
done

cd "$(cd "$(dirname "$0")/.." && pwd)"
command -v git >/dev/null || die "git is not installed"
[ -z "$(git status --porcelain --untracked-files=no)" ] || die "the work tree has uncommitted changes: a release is a commit"
commit="$(git rev-parse HEAD)"
git fetch -q origin main 2>/dev/null || true
git merge-base --is-ancestor "$commit" origin/main 2>/dev/null || die "commit ${commit:0:7} is not on origin/main: push it first"
[ "$(git rev-parse --is-shallow-repository)" = "false" ] || die "this is a shallow clone: the release number counts every commit (fetch the whole history)"
n="$(git rev-list --count "$commit")"
tag="1.3.$n"
jar="target/agent-1.3-SNAPSHOT.jar"

if [ "$BUILD" = 1 ]; then
    bash prebuild.sh
    mvn -B package "-Dcresco.release=$tag"
fi
[ -f "$jar" ] || die "$jar is not built (use --build)"
mf="$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r')"
rel="$(printf '%s\n' "$mf" | sed -n 's/^Cresco-Release: //p' | head -1)"
impl="$(printf '%s\n' "$mf" | sed -n 's/^Implementation-Version: //p' | head -1)"
[ "$rel" = "$tag" ] || die "$jar was built as '${rel:-no release}', not as $tag (use --build, or mvn -B package -Dcresco.release=$tag)"
case "$impl" in *".${commit:0:5}."*) ;; *) die "$jar was built from another commit ($impl; this is ${commit:0:7}): use --build" ;; esac
unzip -p "$jar" META-INF/cresco/bundles.lock | cmp -s - bundles.lock || die "$jar does not carry this commit's bundles.lock: use --build"

out="target/release-$tag"
mkdir -p "$out"
cp "$jar" "$out/agent-$tag.jar"
sum="$(sha256 "$out/agent-$tag.jar")"
echo "$sum  agent-$tag.jar" > "$out/agent-$tag.jar.sha256"
cp bundles.lock "$out/bundles.lock"
files=("$out/agent-$tag.jar" "$out/agent-$tag.jar.sha256" "$out/bundles.lock")
if [ -f target/agent-1.3-SNAPSHOT.cdx.json ]; then
    cp target/agent-1.3-SNAPSHOT.cdx.json "$out/agent-$tag.cdx.json"
    files+=("$out/agent-$tag.cdx.json")
fi
{
    echo "Cresco agent $tag: commit $commit, build $impl."
    echo "Built with $(java -version 2>&1 | head -1) on $(uname -s)."
    echo
    echo "SHA-256 of agent-$tag.jar: \`$sum\`"
    echo
    echo "This release is never replaced. \`java -jar agent-$tag.jar --version\` prints the release, the build and the"
    echo "embedded Cresco bundles; \`scripts/rebuild-check.sh agent-$tag.jar\` in a checkout of this tag checks the jar"
    echo "against \`bundles.lock\`:"
    echo
    echo '```'
    grep -v '^#' bundles.lock
    echo '```'
} > "$out/notes.md"

echo "release: $tag = commit ${commit:0:7}, $(basename "$jar") SHA-256 $sum"
[ "$DRY" = 0 ] || { echo "release: dry run; the files are in $out"; exit 0; }
command -v gh >/dev/null || die "the GitHub CLI (gh) is not installed"
if gh release view "$tag" >/dev/null 2>&1; then
    echo "release: $tag is published already and is not replaced"
else
    gh release create "$tag" "${files[@]}" --target "$commit" --title "Cresco Agent $tag" --notes-file "$out/notes.md"
    echo "release: published $tag"
fi
if [ "$ALIAS" = 1 ]; then
    gh release view 1.3-SNAPSHOT >/dev/null 2>&1 \
        || gh release create 1.3-SNAPSHOT --target "$commit" --title "Cresco Agent" --notes "The latest 1.3 release (now $tag). It is replaced with every release; name a 1.3.<n> release to pin one."
    gh release upload 1.3-SNAPSHOT "$jar" --clobber
    gh release edit 1.3-SNAPSHOT --notes "The latest 1.3 release: $tag (commit ${commit:0:7}, SHA-256 $sum). It is replaced with every release; name a 1.3.<n> release to pin one."
    echo "release: 1.3-SNAPSHOT now carries $tag"
fi
