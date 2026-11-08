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

expected_assets=$(for platform in "${PLATFORMS[@]}"; do echo "EduGeyser-$platform.jar"; done | sort)

# --- GitHub: the draft, the commit it targets and its notes ---

github_release=$(gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
    --jq ".[] | select(.tag_name == \"$TAG\") | @json" | head -n 1)
[ -n "$github_release" ] || fail "GitHub has no release $TAG. The Release workflow drafts one after each master build."
github_id=$(jq -r '.id' <<<"$github_release")
github_draft=$(jq -r '.draft' <<<"$github_release")
sha=$(jq -r '.target_commitish' <<<"$github_release")
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || fail "GitHub's release $TAG targets '$sha', not a commit"
[ "$(jq -r '.assets[] | select(.state == "uploaded") | .name' <<<"$github_release" | sort)" = "$expected_assets" ] \
    || fail "GitHub's release $TAG does not have all jars. Rerun the Release workflow for its build."
jq -r '.body // ""' <<<"$github_release" > notes.md

if [ "$github_draft" = false ]; then
    echo "$TAG is already published on GitHub, which publishes last"
    exit 0
fi

# --- Codeberg: the same notes, then published ---

codeberg_release=$(codeberg GET "/releases?limit=50" | jq -c --arg tag "$TAG" '[.[] | select(.tag_name == $tag)] | first // empty')
[ -n "$codeberg_release" ] || fail "Codeberg has no release $TAG. Rerun the Release workflow for its build."
[ "$(jq -r '.assets[].name' <<<"$codeberg_release" | sort)" = "$expected_assets" ] \
    || fail "Codeberg's release $TAG does not have all jars. Rerun the Release workflow for its build."
codeberg PATCH "/releases/$(jq -r '.id' <<<"$codeberg_release")" -H 'Content-Type: application/json' \
    -d "$(jq -n --rawfile body notes.md '{body: $body, draft: false}')" >/dev/null
[ "$(codeberg GET "/tags/$TAG" | jq -r '.commit.sha')" = "$sha" ] || fail "Codeberg's tag $TAG does not point at $sha"

# --- GitHub last ---

gh api -X PATCH "repos/$GITHUB_REPOSITORY/releases/$github_id" -F draft=false -f make_latest=true >/dev/null
[ "$(gh api "repos/$GITHUB_REPOSITORY/commits/$TAG" --jq .sha)" = "$sha" ] || fail "GitHub's tag $TAG does not point at $sha"
echo "Published $TAG on GitHub and Codeberg"
