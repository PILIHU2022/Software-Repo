#!/usr/bin/env bash
#
# Regression test for scripts/publish-fdroid.sh.
#
# The CI matrix can hand the publish step an APK for a package/versionCode
# that already exists in fdroid/repo (rerun of the same upstream version, a
# retried job, or a local build passed alongside the artifact). Publishing
# must replace that binary rather than leaving two files with the same
# versionCode, otherwise the index may keep pointing at the stale one.
#
# This test works on a throwaway copy of the repository configuration:
#   1. publish an APK, verify one file + one index entry;
#   2. publish a byte-different APK with the same package/versionCode from a
#      nested directory, verify the old file is gone and the index hash points
#      at the new bytes;
#   3. publish two same-version APKs in one directory, verify they collapse.
#
# Usage: scripts/test-publish-fdroid.sh <apk>
#
# Environment:
#   FDROID_BIN         fdroid executable (default: fdroid from PATH)
#   ANDROID_HOME       Android SDK (default: ../.toolchain/android-sdk if present)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

[[ $# -eq 1 ]] || die "usage: $0 <apk>"
APK="$1"
[[ -f "$APK" ]] || die "APK not found: $APK"
APK="$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"

if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/../.toolchain/android-sdk" ]]; then
    export ANDROID_HOME="$ROOT/../.toolchain/android-sdk"
fi
FDROID_BIN="${FDROID_BIN:-fdroid}"
command -v jq >/dev/null 2>&1 || die "jq is required"
command -v sha256sum >/dev/null 2>&1 || die "sha256sum is required"

find_build_tool() {
    local tool="$1" candidate
    if candidate="$(command -v "$tool" 2>/dev/null)" && [[ -n "$candidate" ]]; then
        printf '%s\n' "$candidate"
        return 0
    fi
    if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/build-tools" ]]; then
        candidate="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" | sort -V | tail -n 1)"
        if [[ -n "$candidate" ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    fi
    return 1
}

AAPT="$(find_build_tool aapt)" || die "aapt not found (set ANDROID_HOME)"
APKSIGNER="$(find_build_tool apksigner)" || die "apksigner not found (set ANDROID_HOME)"
command -v python3 >/dev/null 2>&1 || die "python3 is required"

read -r PKG CODE < <("$AAPT" dump badging "$APK" \
    | sed -nE "s/^package: name='([^']*)' versionCode='([0-9]+)'.*/\1 \2/p" \
    | head -n 1)
[[ -n "${PKG:-}" && -n "${CODE:-}" ]] || die "could not read package/versionCode from $APK"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

TESTROOT="$TMP/repo-root"
mkdir -p "$TESTROOT/scripts" "$TESTROOT/signing" "$TESTROOT/fdroid/repo"
cp "$ROOT/scripts/publish-fdroid.sh" "$TESTROOT/scripts/"
cp "$ROOT/signing/patchwork-test.jks" "$TESTROOT/signing/"
cp "$ROOT/fdroid/config.yml" "$TESTROOT/fdroid/"
cp -r "$ROOT/fdroid/metadata" "$TESTROOT/fdroid/"
touch "$TESTROOT/fdroid/repo/.gitkeep"
chmod +x "$TESTROOT/scripts/publish-fdroid.sh"

publish() {
    local apk_dir="$1"
    echo "--- publishing from $apk_dir"
    FDROID_BIN="$FDROID_BIN" ANDROID_HOME="${ANDROID_HOME:-}" \
        "$TESTROOT/scripts/publish-fdroid.sh" --apk-dir "$apk_dir"
}

repo_apks() {
    find "$TESTROOT/fdroid/repo" -maxdepth 1 -type f -name '*.apk' | LC_ALL=C sort
}

assert_single_apk() {
    local expected_sha="$1" context="$2" count
    mapfile -t files < <(repo_apks)
    count="${#files[@]}"
    [[ "$count" -eq 1 ]] \
        || die "$context: expected exactly 1 APK in repo, found $count: ${files[*]}"
    local actual_sha
    actual_sha="$(sha256sum "${files[0]}" | awk '{print $1}')"
    [[ "$actual_sha" == "$expected_sha" ]] \
        || die "$context: repo APK hash $actual_sha != expected $expected_sha"
}

assert_index_version() {
    local expected_sha="$1" context="$2" versions entry_sha
    # index-v2 keys each version by its APK sha256; versionCode lives in the
    # embedded manifest, so count the entries for this versionCode and make
    # sure exactly one remains after the republish.
    versions="$(jq -r --arg pkg "$PKG" --argjson code "$CODE" \
        '[.packages[$pkg].versions[] | select(.manifest.versionCode == $code)] | length' \
        "$TESTROOT/fdroid/repo/index-v2.json")"
    [[ "$versions" == "1" ]] \
        || die "$context: expected 1 index entry with versionCode $CODE for $PKG, found $versions"
    entry_sha="$(jq -r --arg pkg "$PKG" --argjson code "$CODE" \
        '[.packages[$pkg].versions[] | select(.manifest.versionCode == $code) | .file.sha256] | first' \
        "$TESTROOT/fdroid/repo/index-v2.json")"
    [[ "$entry_sha" == "$expected_sha" ]] \
        || die "$context: index hash $entry_sha != expected $expected_sha"
}

# --- 1. first publish -------------------------------------------------------
mkdir -p "$TMP/in1"
cp "$APK" "$TMP/in1/first.apk"
publish "$TMP/in1"
SHA1="$(sha256sum "$TMP/in1/first.apk" | awk '{print $1}')"
assert_single_apk "$SHA1" "first publish"
assert_index_version "$SHA1" "first publish"
echo "PASS: first publish has one APK and one index entry ($PKG $CODE)"

# Build a second APK that keeps the same package/versionCode but has
# different bytes and a valid signature (a real rebuild would differ this
# way). Appending raw bytes breaks aapt's zip parsing, so add a zip comment
# and re-sign the copy with the test key.
modify_apk() {
    local src="$1" dst="$2"
    cp "$src" "$dst"
    python3 - "$dst" <<'PY'
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "a") as archive:
    archive.comment = b"patchwork-rebuild-marker"
PY
    "$APKSIGNER" sign \
        --ks "$TESTROOT/signing/patchwork-test.jks" \
        --ks-pass pass:patchwork123 \
        --key-pass pass:patchwork123 \
        --ks-key-alias patchwork \
        --out "$dst.signed" "$dst"
    mv -f "$dst.signed" "$dst"
}

# --- 2. same versionCode, different bytes, nested directory ----------------
mkdir -p "$TMP/in2/nested/deeper"
modify_apk "$APK" "$TMP/in2/nested/deeper/second.apk"
SHA2="$(sha256sum "$TMP/in2/nested/deeper/second.apk" | awk '{print $1}')"
[[ "$SHA1" != "$SHA2" ]] || die "test setup failed: APK copies are identical"
publish "$TMP/in2"
assert_single_apk "$SHA2" "republish same versionCode"
assert_index_version "$SHA2" "republish same versionCode"
echo "PASS: republished $PKG $CODE replaced the stale APK and index hash"

# --- 3. duplicate inputs in one directory ----------------------------------
mkdir -p "$TMP/in3"
cp "$APK" "$TMP/in3/a.apk"
modify_apk "$APK" "$TMP/in3/z.apk"
SHA3="$(sha256sum "$TMP/in3/z.apk" | awk '{print $1}')"
publish "$TMP/in3"
assert_single_apk "$SHA3" "duplicate input directory"
assert_index_version "$SHA3" "duplicate input directory"
echo "PASS: duplicate APKs for $PKG $CODE collapsed to one"

# The repository index must stay signed after repeated updates.
[[ -f "$TESTROOT/fdroid/repo/entry.jar" ]] \
    || die "fdroid update did not produce entry.jar"
command -v unzip >/dev/null 2>&1 || die "unzip is required"
unzip -l "$TESTROOT/fdroid/repo/entry.jar" \
    | grep -qE 'META-INF/[^/]+\.(RSA|DSA|EC)$' \
    || die "entry.jar has no signature block"
echo "PASS: entry.jar is signed"

echo "All publish-fdroid.sh dedupe tests passed."
