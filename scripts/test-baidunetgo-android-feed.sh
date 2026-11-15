#!/usr/bin/env bash
#
# Smoke test for scripts/baidunetgo-android-feed.py, the generator behind
# fdroid/baidunetgo/update.json.
#
# It builds throwaway dist directories in a temporary directory (the generator
# only reads build-info.txt) and checks the feed it writes:
#   1. a full build-info publishes the exact document shape the Android
#      updater reads — versionCode as a JSON number, versionName, the release
#      asset URL for the APK and the F-Droid listing URL;
#   2. without --notes the notes are synthesized from build-info.txt;
#   3. a dist without a usable build-info.txt (missing file, non-numeric
#      version_code or no apk= line) fails the run and is named in the error;
#   4. the feed is 0644, ends with a newline, keeps its keys sorted and leaves
#      no temporary file behind.
#
# Usage: scripts/test-baidunetgo-android-feed.sh
#
# Environment:
#   PYTHON     python3 executable (default: python3 from PATH)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GEN="$ROOT/scripts/baidunetgo-android-feed.py"
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

TAG="baidunetgo-android-0.8.3-patchwork-abc1234"
REPO="PILIHU2022/Software-Repo"
VERSION_CODE=80341
VERSION_NAME="0.8.3-patchwork"
APK_NAME="BaiduNetGo-$VERSION_NAME.apk"
ASSET_BASE="https://github.com/$REPO/releases/download/$TAG"
FDROID_URL="https://software.sparkzh.top/repo?p=com.pengstem.baidunetgo"

# new_dist DIR [KEY=VALUE...]: make DIR with a build-info.txt from the pairs.
new_dist() {
    local dir="$1" pair
    shift
    mkdir -p "$dir"
    for pair in "$@"; do
        printf '%s\n' "$pair" >>"$dir/build-info.txt"
    done
}

# feed DIST OUT [EXTRA-ARGS...]: run the generator.
feed() {
    local dist="$1" out="$2"
    shift 2
    "$PYTHON" "$GEN" --dist "$dist" --tag "$TAG" --repo "$REPO" --out "$out" "$@"
}

mode_of() {
    stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"
}

# --- 1. the exact document shape --------------------------------------------

full="$TMP/dist-full"
new_dist "$full" \
    "app=baidunetgo-android" \
    "upstream_commit=0123456789abcdef0123456789abcdef01234567" \
    "patch_count=41" \
    "version_code=$VERSION_CODE" \
    "version_name=$VERSION_NAME" \
    "apk=$APK_NAME"
printf '%s\n' '# 0.8.3' '' '- 修复备份' >"$TMP/notes.md"

stdout="$(feed "$full" "$TMP/full.json" --notes "$TMP/notes.md")"
[[ "$stdout" == "feed: https://software.sparkzh.top/baidunetgo/update.json (versionCode $VERSION_CODE)" ]] \
    || fail "unexpected stdout: '$stdout'"
"$PYTHON" -m json.tool "$TMP/full.json" >/dev/null \
    || fail "the feed is not valid JSON"
keys="$(jq -r 'keys_unsorted | join(",")' "$TMP/full.json")"
[[ "$keys" == "apk,fdroid,notes,versionCode,versionName" ]] \
    || fail "the feed has unexpected keys: $keys"
[[ "$(jq -r '.versionCode' "$TMP/full.json")" == "$VERSION_CODE" ]] \
    || fail "versionCode is '$(jq -r '.versionCode' "$TMP/full.json")', expected $VERSION_CODE"
jq -e ".versionCode | type == \"number\"" "$TMP/full.json" >/dev/null \
    || fail "versionCode must be a JSON number, not a string"
[[ "$(jq -r '.versionName' "$TMP/full.json")" == "$VERSION_NAME" ]] \
    || fail "versionName is '$(jq -r '.versionName' "$TMP/full.json")', expected $VERSION_NAME"
[[ "$(jq -r '.apk' "$TMP/full.json")" == "$ASSET_BASE/$APK_NAME" ]] \
    || fail "wrong apk URL: $(jq -r '.apk' "$TMP/full.json")"
[[ "$(jq -r '.fdroid' "$TMP/full.json")" == "$FDROID_URL" ]] \
    || fail "wrong fdroid URL: $(jq -r '.fdroid' "$TMP/full.json")"
[[ "$(jq -r '.notes' "$TMP/full.json")" == "# 0.8.3

- 修复备份" ]] || fail "notes are not the --notes text: $(jq -r '.notes' "$TMP/full.json")"
ok "a full build-info publishes the exact shape the Android updater reads"

# --- 2. notes synthesized from build-info.txt -------------------------------

meta="$TMP/dist-meta"
new_dist "$meta" \
    "app=baidunetgo-android" \
    "upstream_commit=0123456789abcdef0123456789abcdef01234567" \
    "patch_count=7" \
    "version_code=$VERSION_CODE" \
    "version_name=$VERSION_NAME" \
    "apk=$APK_NAME"
feed "$meta" "$TMP/meta.json" >/dev/null
notes="$(jq -r .notes "$TMP/meta.json")"
[[ "$notes" == *"0123456"* ]] \
    || fail "synthesized notes lack the short upstream commit: $notes"
[[ "$notes" == *"7 个补丁"* ]] \
    || fail "synthesized notes lack the patch count: $notes"
[[ "$notes" == *"Android"* ]] \
    || fail "synthesized notes lack the platform hint: $notes"
ok "without --notes the notes come from build-info.txt"

# --- 3. unusable build-info fails the run ------------------------------------

empty_dir="$TMP/dist-empty"
mkdir -p "$empty_dir"
if feed "$empty_dir" "$TMP/empty.json" >"$TMP/empty.out" 2>"$TMP/empty.err"; then
    fail "a dist without build-info.txt should fail"
fi
grep -qi 'build-info' "$TMP/empty.err" \
    || fail "the error does not name build-info.txt: $(cat "$TMP/empty.err")"
[[ ! -e "$TMP/empty.json" ]] || fail "the failed run still wrote a feed"

bad_code="$TMP/dist-bad-code"
new_dist "$bad_code" "version_code=803xx" "version_name=$VERSION_NAME" "apk=$APK_NAME"
if feed "$bad_code" "$TMP/bad-code.json" >"$TMP/bad-code.out" 2>"$TMP/bad-code.err"; then
    fail "a non-numeric version_code should fail"
fi
grep -qi 'version_code' "$TMP/bad-code.err" \
    || fail "the error does not name version_code: $(cat "$TMP/bad-code.err")"
[[ ! -e "$TMP/bad-code.json" ]] || fail "the failed run still wrote a feed"

no_apk="$TMP/dist-no-apk"
new_dist "$no_apk" "version_code=$VERSION_CODE" "version_name=$VERSION_NAME"
if feed "$no_apk" "$TMP/no-apk.json" >"$TMP/no-apk.out" 2>"$TMP/no-apk.err"; then
    fail "a build-info without an apk= line should fail"
fi
grep -qi 'apk' "$TMP/no-apk.err" \
    || fail "the error does not name the missing apk line: $(cat "$TMP/no-apk.err")"
[[ ! -e "$TMP/no-apk.json" ]] || fail "the failed run still wrote a feed"
ok "unusable build-info.txt fails the run and is named"

# --- 4. file format ---------------------------------------------------------

mode="$(mode_of "$TMP/full.json")"
[[ "$mode" == "644" ]] || fail "feed mode is $mode, expected 644"
[[ -z "$(tail -c 1 "$TMP/full.json" | tr -d '\n')" ]] \
    || fail "the feed does not end with a newline"
first_key="$(sed -n '2s/^  "\([^"]*\)".*/\1/p' "$TMP/full.json")"
[[ "$first_key" == "apk" ]] \
    || fail "feed keys are not sorted: first key is '$first_key'"
leftovers="$(find "$TMP" -name '.update.json.*' -print)"
[[ -z "$leftovers" ]] || fail "temporary files left behind: $leftovers"
ok "the feed is 0644, newline-terminated and leaves no temporary file"

echo "ok ($cases cases)"
