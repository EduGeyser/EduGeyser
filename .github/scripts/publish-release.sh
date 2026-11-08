#!/usr/bin/env bash
# Publishes a release that release.sh drafted: the notes of the GitHub draft, as edited there, go
# to the Codeberg draft, then Codeberg publishes, then GitHub, so GitHub only shows a release that
# Codeberg has too. Run by .github/workflows/publish-release.yml with the tag, such as
# v2.28.0-edu. A rerun finishes a publication that stopped halfway.
set -euo pipefail

: "${TAG:?}" "${GH_TOKEN:?}" "${GITHUB_REPOSITORY:?}" "${CODEBERG_REPO:?}"
PLATFORMS=(BungeeCord Fabric NeoForge Spigot Standalone Velocity ViaProxy)
CODEBERG_API="https://codeberg.org/api/v1/repos/$CODEBERG_REPO"

fail() {
    echo "::error::$*"
    exit 1
}

[ -n "${CODEBERG_TOKEN:-}" ] || fail "The CODEBERG_RELEASE_TOKEN secret is not set, so the release cannot reach Codeberg"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-edu$ ]] || fail "'$TAG' is not a release tag such as v2.28.0-edu"

# Prints the response body and fails on any HTTP error
codeberg() {
    local method=$1 path=$2
    shift 2
    curl -sS --fail-with-body -X "$method" -H "Authorization: token $CODEBERG_TOKEN" "$@" "$CODEBERG_API$path"
}

required_assets=$(for platform in "${PLATFORMS[@]}"; do echo "EduGeyser-$platform.jar"; done | sort)

# Prints the jars that the asset names on stdin lack; other assets may be there too
missing_assets() {
    comm -23 <(echo "$required_assets") <(sort -u)
}

# --- GitHub: the draft, the commit it targets and its notes ---

github_release=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
    --jq ".[] | select(.tag_name == \"$TAG\") | @json" | head -n 1)
[ -n "$github_release" ] || fail "GitHub has no release $TAG. The Release workflow drafts one after each master build."
github_id=$(jq -r '.id' <<<"$github_release")
github_draft=$(jq -r '.draft' <<<"$github_release")
sha=$(jq -r '.target_commitish' <<<"$github_release")
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || fail "GitHub's release $TAG targets '$sha', not a commit"
[ -z "$(jq -r '.assets[] | select(.state == "uploaded") | .name' <<<"$github_release" | missing_assets)" ] \
    || fail "GitHub's release $TAG does not have all jars. Rerun the Release workflow for its build."
# -j: the notes exactly as GitHub holds them, without a newline added
jq -j '.body // ""' <<<"$github_release" > notes.md
# Codeberg ignores an empty body, which would leave its generated notes in place
[ -s notes.md ] || fail "GitHub's release $TAG has no notes"

if [ "$github_draft" = false ]; then
    echo "$TAG is already published on GitHub, which publishes last"
    exit 0
fi

# --- Codeberg: the same notes, then published ---

status=$(curl -sS -o codeberg-release.json -w '%{http_code}' -H "Authorization: token $CODEBERG_TOKEN" \
    "$CODEBERG_API/releases/tags/$TAG")
case "$status" in
    200) ;;
    404) fail "Codeberg has no release $TAG. Rerun the Release workflow for its build." ;;
    *) fail "Codeberg answered HTTP $status when looking up the release $TAG" ;;
esac
codeberg_release=$(cat codeberg-release.json)
rm -f codeberg-release.json
[ -z "$(jq -r '.assets[].name' <<<"$codeberg_release" | missing_assets)" ] \
    || fail "Codeberg's release $TAG does not have all jars. Rerun the Release workflow for its build."
codeberg PATCH "/releases/$(jq -r '.id' <<<"$codeberg_release")" -H 'Content-Type: application/json' \
    -d "$(jq -n --rawfile body notes.md '{body: $body, draft: false}')" >/dev/null
[ "$(codeberg GET "/tags/$TAG" | jq -r '.commit.sha')" = "$sha" ] || fail "Codeberg's tag $TAG does not point at $sha"

# --- GitHub last ---

# Latest only when no published release has a higher version, so publishing an older draft
# after a newer one leaves the newer one as latest
latest=$(gh api "repos/$GITHUB_REPOSITORY/releases/latest" --jq .tag_name 2>/dev/null || true)
make_latest=true
if [ -n "$latest" ] && [ "$latest" != "$TAG" ] \
    && [ "$(printf '%s\n%s\n' "$latest" "$TAG" | sort -V | tail -n 1)" = "$latest" ]; then
    make_latest=false
fi
gh api -X PATCH "repos/$GITHUB_REPOSITORY/releases/$github_id" -F draft=false -f make_latest="$make_latest" >/dev/null
[ "$(gh api "repos/$GITHUB_REPOSITORY/commits/$TAG" --jq .sha)" = "$sha" ] || fail "GitHub's tag $TAG does not point at $sha"
echo "Published $TAG on GitHub and Codeberg"
