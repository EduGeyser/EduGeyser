#!/usr/bin/env bash
# Releases one master build on GitHub and Codeberg with the same tag, notes and jars.
# Run by .github/workflows/release.yml. Both releases stay drafts until every upload has worked,
# and a rerun finishes a release that failed halfway instead of starting a second one.
set -euo pipefail

: "${SHA:?}" "${JARS:?}" "${GH_TOKEN:?}" "${GITHUB_REPOSITORY:?}" "${CODEBERG_REPO:?}"
PLATFORMS=(BungeeCord Fabric NeoForge Spigot Standalone Velocity ViaProxy)
CODEBERG_API="https://codeberg.org/api/v1/repos/$CODEBERG_REPO"

fail() {
    echo "::error::$*"
    exit 1
}

# DRY_RUN=1 checks the jars and prints the tag and notes, and stops before changing anything
[ -n "${CODEBERG_TOKEN:-}" ] || [ "${DRY_RUN:-}" = 1 ] \
    || fail "The CODEBERG_RELEASE_TOKEN secret is not set, so the release cannot reach Codeberg"

# Prints the response body and fails on any HTTP error
codeberg() {
    local method=$1 path=$2
    shift 2
    curl -sS --fail-with-body -X "$method" -H "Authorization: token $CODEBERG_TOKEN" "$@" "$CODEBERG_API$path"
}

codeberg_status() {
    curl -sS -o /dev/null -w '%{http_code}' -H "Authorization: token $CODEBERG_TOKEN" "$CODEBERG_API$1"
}

# --- The jars must come from this commit, carry no build number and bundle the extension ---

prop() {
    sed -n "s/^$1=//p" <<<"$props" | tr -d '\r'
}

for platform in "${PLATFORMS[@]}"; do
    jar="$JARS/EduGeyser-$platform.jar"
    [ -f "$jar" ] || fail "The build has no $(basename "$jar")"
    props=$(unzip -p "$jar" git.properties) || fail "$jar has no git.properties"
    [ "$(prop git.commit.id)" = "$SHA" ] || fail "$jar was built from $(prop git.commit.id), not $SHA"
    [ "$(prop git.branch)" = master ] || fail "$jar was built from branch $(prop git.branch)"
    # GeyserMC's news targets its own build numbers; releases have always carried none
    [ "$(prop git.build.number)" = -1 ] || fail "$jar has build number $(prop git.build.number)"
    unzip -l "$jar" extensions-bundled/edu.jar >/dev/null || fail "$jar does not bundle the extension"
done

extension_version=$(unzip -p "$JARS/EduGeyser-Velocity.jar" extensions-bundled/edu.jar > edu.jar \
    && unzip -p edu.jar extension.yml | sed -n 's/^version: *"\{0,1\}\([^"]*\)"\{0,1\}[[:space:]]*$/\1/p')
rm -f edu.jar
[ -n "$extension_version" ] || fail "Cannot read the bundled extension's version"

# --- Version: the next patch on the line in .github/edugeyser-release.properties ---

line=$(sed -n 's/^version=//p' .github/edugeyser-release.properties | tr -d '\r[:space:]')
[[ "$line" =~ ^[0-9]+\.[0-9]+$ ]] || fail ".github/edugeyser-release.properties must hold version=MAJOR.MINOR, not '$line'"

# id, tag, target commit and draft state of every GitHub release, drafts included
github_releases=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
    --jq '.[] | [.id, .tag_name, .target_commitish, (.draft | tostring)] | @tsv')

# A tag on this commit, or a draft for it, means an earlier run already took a version
tag=$(git tag --points-at "$SHA" --list 'v*-edu' | head -n 1)
if [ -z "$tag" ]; then
    tag=$(awk -F'\t' -v sha="$SHA" '$3 == sha && $4 == "true" { print $2; exit }' <<<"$github_releases")
fi
if [ -z "$tag" ]; then
    last=$( { git tag --list "v$line.*-edu"; cut -f2 <<<"$github_releases"; } \
        | sed -n "s/^v${line//./\\.}\.\([0-9][0-9]*\)-edu$/\1/p" | sort -n | tail -n 1)
    tag="v$line.$(( ${last:--1} + 1 ))-edu"
fi
echo "Releasing $SHA as $tag"

# --- Notes: every commit since the previous release, with its author ---

previous=$(git describe --tags --abbrev=0 --match 'v*-edu' "$SHA^" 2>/dev/null || true)
{
    if [ -n "$previous" ]; then
        echo "Changes since $previous:"
        echo
        git log --reverse --abbrev=9 --format='- %h %s (%an)' "$previous..$SHA"
        echo
    fi
    echo "Bundled EduGeyser extension: $extension_version"
} > notes.md

if [ "${DRY_RUN:-}" = 1 ]; then
    echo "Dry run, nothing released. Notes:"
    cat notes.md
    exit 0
fi

# --- Codeberg must accept the token before anything is created ---

access=$(curl -sS -o codeberg-repo.json -w '%{http_code}' -H "Authorization: token $CODEBERG_TOKEN" "$CODEBERG_API")
[ "$access" = 200 ] || fail "Codeberg answered HTTP $access for $CODEBERG_REPO. Check the CODEBERG_RELEASE_TOKEN secret."
[ "$(jq -r '.permissions.push' codeberg-repo.json)" = true ] || fail "The CODEBERG_RELEASE_TOKEN secret cannot write to $CODEBERG_REPO"
[ "$(jq -r '.has_releases' codeberg-repo.json)" = true ] || fail "Releases are turned off in $CODEBERG_REPO's settings"
rm -f codeberg-repo.json

# --- GitHub: a draft with all jars ---

github_id=""
github_published=false
while IFS=$'\t' read -r id release_tag _ draft; do
    [ "$release_tag" = "$tag" ] || continue
    if [ "$draft" = false ]; then
        github_published=true
        github_id=$id
    else
        # Left by a run that failed before publishing; its uploads may be incomplete
        gh api -X DELETE "repos/$GITHUB_REPOSITORY/releases/$id" >/dev/null
    fi
done <<<"$github_releases"

if [ "$github_published" = false ]; then
    gh release create "$tag" "$JARS"/EduGeyser-*.jar --draft --target "$SHA" --title "$tag" --notes-file notes.md
    github_id=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
        --jq ".[] | select(.tag_name == \"$tag\" and .draft) | .id" | head -n 1)
    [ -n "$github_id" ] || fail "Cannot find the GitHub draft for $tag"
fi

# --- Codeberg: the same release ---

# The commit reaches Codeberg with the mirror push, which can come a little later
for attempt in $(seq 1 40); do
    status=$(codeberg_status "/git/commits/$SHA")
    [ "$status" = 200 ] && break
    [ "$status" = 404 ] || fail "Codeberg answered HTTP $status when looking up $SHA"
    [ "$attempt" -eq 40 ] && fail "Codeberg still does not have $SHA after 20 minutes. Push master to Codeberg, then rerun this job."
    sleep 30
done

if [ "$(codeberg_status "/tags/$tag")" = 200 ]; then
    tagged=$(codeberg GET "/tags/$tag" | jq -r '.commit.sha')
    [ "$tagged" = "$SHA" ] || fail "Codeberg's tag $tag points at $tagged, not $SHA"
fi

codeberg_release=$(codeberg GET "/releases?limit=50" | jq -c --arg tag "$tag" '[.[] | select(.tag_name == $tag)] | first // empty')
if [ -n "$codeberg_release" ] && [ "$(jq -r '.draft' <<<"$codeberg_release")" = true ]; then
    codeberg DELETE "/releases/$(jq -r '.id' <<<"$codeberg_release")" >/dev/null
    codeberg_release=""
fi

if [ -z "$codeberg_release" ]; then
    codeberg_id=$(codeberg POST /releases -H 'Content-Type: application/json' \
        -d "$(jq -n --arg tag "$tag" --arg sha "$SHA" --rawfile body notes.md \
            '{tag_name: $tag, target_commitish: $sha, name: $tag, body: $body, draft: true, prerelease: false}')" \
        | jq -r '.id')
    for jar in "$JARS"/EduGeyser-*.jar; do
        codeberg POST "/releases/$codeberg_id/assets?name=$(basename "$jar")" \
            -F "attachment=@$jar;type=application/java-archive" >/dev/null
    done
    codeberg PATCH "/releases/$codeberg_id" -H 'Content-Type: application/json' -d '{"draft": false}' >/dev/null
fi

[ "$(codeberg GET "/tags/$tag" | jq -r '.commit.sha')" = "$SHA" ] || fail "Codeberg's tag $tag does not point at $SHA"

# --- Publish on GitHub last, so it only shows once Codeberg has the release too ---

if [ "$github_published" = false ]; then
    gh api -X PATCH "repos/$GITHUB_REPOSITORY/releases/$github_id" -F draft=false -f make_latest=true >/dev/null
fi
[ "$(gh api "repos/$GITHUB_REPOSITORY/commits/$tag" --jq .sha)" = "$SHA" ] || fail "GitHub's tag $tag does not point at $SHA"
echo "Released $tag on GitHub and Codeberg"
