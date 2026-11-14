#!/usr/bin/env bash
#
# Smoke test for scripts/baidunetgo-feed.py, the generator behind
# desktop/baidunetgo/update.json.
#
# It builds throwaway dist directories in a temporary directory (empty files
# with the real installer names — the generator only reads names) and checks
# the feed it writes:
#   1. a full dist publishes both x86 platforms with release asset URLs, the
#      --notes text and parseable JSON;
#   2. a dist with only the setup exe, or only the .deb, falls back to it;
#   3. a requested platform without an installer fails the run and is named in
#      the error;
#   4. without --notes the notes are synthesized from build-info.txt;
#   5. the feed is 0644, ends with a newline, keeps its keys sorted and leaves
#      no temporary file behind.
#
# Usage: scripts/test-baidunetgo-feed.sh
#
# Environment:
#   PYTHON     python3 executable (default: python3 from PATH)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$ROOT/scripts/baidunetgo-feed.py"
PYTHON="${PYTHON:-python3}"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v "$PYTHON" >/dev/null 2>&1 || {
    echo "ERROR: $PYTHON not found" >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    echo "ERROR: jq is required" >&2
    exit 1
}

cases=0
fail() {
    echo "FAIL: $*" >&2
    exit 1
}
ok() {
    cases=$((cases + 1))
    echo "ok ${cases}: $*"
}

TAG="baidunetgo-desktop-0.8.4-abc1234"
REPO="PILIHU2022/Software-Repo"
VERSION="0.8.4"
ASSET_BASE="https://github.com/$REPO/releases/download/$TAG"
RAW_FEED="https://raw.githubusercontent.com/$REPO/main/desktop/baidunetgo/update.json"

# new_dist DIR NAME...: make DIR and one empty file per NAME inside it.
new_dist() {
    local dir="$1" name
    shift
    mkdir -p "$dir"
    for name in "$@"; do
        : >"$dir/$name"
    done
}

# feed DIST NOTES-FILE-OR-EMPTY OUT [EXTRA-ARGS...]: run the generator.
feed() {
    local dist="$1" notes="$2" out="$3"
    shift 3
    if [[ -n "$notes" ]]; then
        "$PYTHON" "$GEN" --dist "$dist" --tag "$TAG" --version "$VERSION" \
            --repo "$REPO" --notes "$notes" --out "$out" "$@"
    else
        "$PYTHON" "$GEN" --dist "$dist" --tag "$TAG" --version "$VERSION" \
            --repo "$REPO" --out "$out" "$@"
    fi
}

mode_of() {
    stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"
}

# --- 1. a full dist ---------------------------------------------------------

full="$TMP/dist-full"
new_dist "$full" \
    "baidunetgo-$VERSION-linux-amd64.tar.gz" \
    "baidunetgo_${VERSION}_amd64.deb" \
    "BaiduNetGo-$VERSION-windows-amd64.zip" \
    "baidunetgo-setup-$VERSION-amd64.exe" \
    "build-info.txt"
printf '%s\n' '# 0.8.4' '' '- 修复登录' >"$TMP/notes.md"

stdout="$(feed "$full" "$TMP/notes.md" "$TMP/full.json")"
[[ "$stdout" == "feed: $RAW_FEED (2 platforms)" ]] \
    || fail "unexpected stdout: '$stdout'"
"$PYTHON" -m json.tool "$TMP/full.json" >/dev/null \
    || fail "the feed is not valid JSON"
[[ "$(jq -r .version "$TMP/full.json")" == "$VERSION" ]] \
    || fail "version is '$(jq -r .version "$TMP/full.json")', expected $VERSION"
[[ "$(jq -r '.url["linux-amd64"]' "$TMP/full.json")" == "$ASSET_BASE/baidunetgo-$VERSION-linux-amd64.tar.gz" ]] \
    || fail "wrong linux URL: $(jq -r '.url["linux-amd64"]' "$TMP/full.json")"
[[ "$(jq -r '.url["windows-amd64"]' "$TMP/full.json")" == "$ASSET_BASE/BaiduNetGo-$VERSION-windows-amd64.zip" ]] \
    || fail "wrong windows URL: $(jq -r '.url["windows-amd64"]' "$TMP/full.json")"
[[ "$(jq -r .notes "$TMP/full.json")" == "# 0.8.4

- 修复登录" ]] || fail "notes are not the --notes text: $(jq -r .notes "$TMP/full.json")"
ok "a full dist publishes both platforms with the --notes text"

# --- 2. fallbacks -----------------------------------------------------------

exe_only="$TMP/dist-exe"
new_dist "$exe_only" \
    "baidunetgo-$VERSION-linux-amd64.tar.gz" \
    "baidunetgo-setup-$VERSION-amd64.exe"
feed "$exe_only" "" "$TMP/exe.json" >/dev/null
[[ "$(jq -r '.url["windows-amd64"]' "$TMP/exe.json")" == "$ASSET_BASE/baidunetgo-setup-$VERSION-amd64.exe" ]] \
    || fail "windows did not fall back to the setup exe: $(jq -r '.url["windows-amd64"]' "$TMP/exe.json")"

deb_only="$TMP/dist-deb"
new_dist "$deb_only" \
    "baidunetgo_${VERSION}_amd64.deb" \
    "BaiduNetGo-$VERSION-windows-amd64.zip"
feed "$deb_only" "" "$TMP/deb.json" >/dev/null
[[ "$(jq -r '.url["linux-amd64"]' "$TMP/deb.json")" == "$ASSET_BASE/baidunetgo_${VERSION}_amd64.deb" ]] \
    || fail "linux did not fall back to the .deb: $(jq -r '.url["linux-amd64"]' "$TMP/deb.json")"
ok "a missing zip or tarball falls back to the setup exe and the .deb"

# --- 3. a missing platform fails the run ------------------------------------

linux_only="$TMP/dist-linux"
new_dist "$linux_only" "baidunetgo-$VERSION-linux-amd64.tar.gz"
if feed "$linux_only" "" "$TMP/missing.json" >"$TMP/missing.out" 2>"$TMP/missing.err"; then
    fail "a dist without a windows installer should fail"
fi
grep -qi 'windows' "$TMP/missing.err" \
    || fail "the error does not name the missing platform: $(cat "$TMP/missing.err")"
[[ ! -e "$TMP/missing.json" ]] || fail "the failed run still wrote a feed"
ok "a requested platform without an installer fails and is named"

# --- 4. notes synthesized from build-info.txt -------------------------------

meta="$TMP/dist-meta"
new_dist "$meta" \
    "baidunetgo-$VERSION-linux-amd64.tar.gz" \
    "BaiduNetGo-$VERSION-windows-amd64.zip"
{
    echo "version=$VERSION"
    echo "upstream_commit=0123456789abcdef0123456789abcdef01234567"
    echo "patch_count=7"
} >"$meta/build-info.txt"
feed "$meta" "" "$TMP/meta.json" >/dev/null
notes="$(jq -r .notes "$TMP/meta.json")"
[[ "$notes" == *"0123456"* ]] \
    || fail "synthesized notes lack the short upstream commit: $notes"
[[ "$notes" == *"7 个补丁"* ]] \
    || fail "synthesized notes lack the patch count: $notes"
[[ "$notes" == *"x86"* ]] \
    || fail "synthesized notes lack the platform hint: $notes"
ok "without --notes the notes come from build-info.txt"

# --- 5. file format ---------------------------------------------------------

mode="$(mode_of "$TMP/full.json")"
[[ "$mode" == "644" ]] || fail "feed mode is $mode, expected 644"
[[ -z "$(tail -c 1 "$TMP/full.json" | tr -d '\n')" ]] \
    || fail "the feed does not end with a newline"
first_key="$(sed -n '2s/^  "\([^"]*\)".*/\1/p' "$TMP/full.json")"
[[ "$first_key" == "notes" ]] \
    || fail "feed keys are not sorted: first key is '$first_key'"
leftovers="$(find "$TMP" -name '.update.json.*' -print)"
[[ -z "$leftovers" ]] || fail "temporary files left behind: $leftovers"
ok "the feed is 0644, newline-terminated and leaves no temporary file"

echo "ok ($cases cases)"
