#!/usr/bin/env bash
#
# Build the BaiduNetGo Android client on top of the same prepared tree as the
# desktop build.
#
# Usage:
#   scripts/build-baidunetgo-android.sh [--out DIR]
#
# The desktop script's prepare phase (scripts/build-baidunetgo.sh --prepare)
# already mirrors upstream, checks out the upstream head and replays the queue
# in desktop/baidunetgo/patches onto it with `git am -3 --keep-cr`; this script
# reuses that tree (.cache/work/baidunetgo) and its .patchwork-prepared marker,
# so both halves of the workflow build from the exact same patched commit. Run
# the prepare step first:
#
#   scripts/build-baidunetgo.sh --prepare
#   scripts/build-baidunetgo-android.sh --out dist/baidunetgo-android
#
# What it does:
#   1. rewrites android/app/build.gradle.kts's versionCode/versionName to the
#      patchwork scheme: versionCode = upstream versionCode × 100 + patch
#      count (803 with 41 patches is 80341 — the same formula build-app.sh
#      uses for the other Android apps, with the queue's patch count as the
#      revision because BaiduNetGo has no apps/<app>/ directory to count
#      commits in), versionName = "<upstream versionName>-patchwork";
#   2. runs ./gradlew :app:assembleRelease (JDK 21 and the Android SDK come
#      from the environment; CI mirrors build.yml's SDK steps);
#   3. signs the unsigned release APK with apksigner and the patchwork
#      keystore — the same committed test key every other Android app here is
#      signed with, overridable through the PATCHWORK_* environment variables
#      documented in build-app.sh. v2/v3 signatures only: minSdk is 26, so a
#      v1 JAR signature would only add bytes (same choice as the ARM-strip
#      re-sign flow in build-app.sh);
#   4. verifies the signature with apksigner and the package, versionCode and
#      versionName with aapt dump badging;
#   5. copies the APK and a build-info.txt into --out and prints
#      PATCHWORK_RESULT_* lines (stable, machine-readable).
#
# Environment:
#   PATCHWORK_KEYSTORE_FILE   signing keystore (default: signing/patchwork-test.jks)
#   PATCHWORK_STORE_PASSWORD  keystore password (default: -patchwork123)
#   PATCHWORK_KEY_ALIAS       key alias (default: patchwork)
#   PATCHWORK_KEY_PASSWORD    key password (default: -patchwork123)
#   ANDROID_HOME              Android SDK (default: ../.toolchain/android-sdk if present)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/.cache/work/baidunetgo"
PREPARED="$WORK/.patchwork-prepared"

usage() {
    # Print the header comment only: stop at the first non-comment line, so
    # documenting another variable above never spills code into --help.
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

OUT="$ROOT/dist/baidunetgo-android"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --out)
            [[ $# -ge 2 ]] || die "--out needs a directory"
            OUT="$2"
            shift 2
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1 (try --help)"
            ;;
    esac
done

[[ -f "$PREPARED" ]] || die "no prepared tree; run scripts/build-baidunetgo.sh --prepare first"
# shellcheck source=/dev/null
source "$PREPARED"
[[ "${patch_count:-}" =~ ^[0-9]+$ ]] || die "prepared state has no usable patch_count"

ANDROID="$WORK/android"
[[ -f "$ANDROID/settings.gradle.kts" ]] || die "no Android project in the prepared tree: $ANDROID"
GRADLE_FILE="$ANDROID/app/build.gradle.kts"
[[ -f "$GRADLE_FILE" ]] || die "gradle file not found in the prepared tree: $GRADLE_FILE"

if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/../.toolchain/android-sdk" ]]; then
    export ANDROID_HOME="$ROOT/../.toolchain/android-sdk"
fi

# --- Version ----------------------------------------------------------------

CODE_RE='^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$'
NAME_RE='^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)"[[:space:]]*$'

# The rewrite below must never double-apply: a previous run over the same
# prepared tree (a failed signing step, a rerun) may have left the patched
# values in the file. Snapshot the untouched file once and restore it first,
# so the patchwork scheme is always computed from upstream's own numbers.
VERSION_SNAPSHOT="$WORK/.patchwork-android-build.gradle.kts.orig"
if [[ -f "$VERSION_SNAPSHOT" ]]; then
    cp -f "$VERSION_SNAPSHOT" "$GRADLE_FILE"
else
    cp -f "$GRADLE_FILE" "$VERSION_SNAPSHOT"
fi

code_matches="$(grep -cE "$CODE_RE" "$GRADLE_FILE" || true)"
[[ "$code_matches" -eq 1 ]] \
    || die "expected exactly one versionCode line in $GRADLE_FILE, found $code_matches"
name_matches="$(grep -cE "$NAME_RE" "$GRADLE_FILE" || true)"
[[ "$name_matches" -eq 1 ]] \
    || die "expected exactly one versionName line in $GRADLE_FILE, found $name_matches"

UPSTREAM_CODE="$(sed -nE "s/${CODE_RE}/\1/p" "$GRADLE_FILE")"
UPSTREAM_NAME="$(sed -nE "s/${NAME_RE}/\1/p" "$GRADLE_FILE")"
[[ "$UPSTREAM_CODE" =~ ^[0-9]+$ ]] || die "could not parse versionCode from $GRADLE_FILE"
[[ -n "$UPSTREAM_NAME" ]] || die "could not parse versionName from $GRADLE_FILE"

# The version scheme above caps the revision at 99: versionCode =
# upstream × 100 + revision, so a three-digit revision would collide with the
# next upstream release. Same limit build-app.sh enforces for the other apps.
if (( 10#$patch_count > 99 )); then
    die "patch_count=$patch_count exceeds the maximum of 99 (versionCode = upstream_code * 100 + patch_count). Squash downstream commits or split the queue."
fi

NEW_CODE=$((10#$UPSTREAM_CODE * 100 + 10#$patch_count))
NEW_NAME="$UPSTREAM_NAME-patchwork"

echo "==> Rewriting $GRADLE_FILE"
echo "    versionCode: $UPSTREAM_CODE -> $NEW_CODE"
echo "    versionName: $UPSTREAM_NAME -> $NEW_NAME"

tmp="$(mktemp "${GRADLE_FILE}.XXXXXX")"
awk -v code="$NEW_CODE" -v name="$NEW_NAME" '
    /^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*[0-9]+[[:space:]]*$/ {
        match($0, /^[[:space:]]*/)
        print substr($0, 1, RLENGTH) "versionCode = " code
        next
    }
    /^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"[^"]*"[[:space:]]*$/ {
        match($0, /^[[:space:]]*/)
        print substr($0, 1, RLENGTH) "versionName = \"" name "\""
        next
    }
    { print }
' "$GRADLE_FILE" >"$tmp"
chmod --reference="$GRADLE_FILE" "$tmp"
mv "$tmp" "$GRADLE_FILE"

grep -qE "^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*${NEW_CODE}[[:space:]]*$" "$GRADLE_FILE" \
    || die "versionCode rewrite failed in $GRADLE_FILE"
grep -qF "versionName = \"$NEW_NAME\"" "$GRADLE_FILE" \
    || die "versionName rewrite failed in $GRADLE_FILE"

# --- Build ------------------------------------------------------------------

echo "==> Building :app:assembleRelease"
chmod +x "$ANDROID/gradlew"
(
    cd "$ANDROID"
    ./gradlew :app:assembleRelease --no-daemon --stacktrace
)

RELEASE_DIR="$ANDROID/app/build/outputs/apk/release"
[[ -d "$RELEASE_DIR" ]] || die "release output directory not found: $RELEASE_DIR"
mapfile -t APKS < <(find "$RELEASE_DIR" -maxdepth 1 -type f -name '*.apk' | LC_ALL=C sort)
[[ ${#APKS[@]} -eq 1 ]] || die "expected exactly one APK in $RELEASE_DIR, found ${#APKS[@]}"
UNSIGNED="${APKS[0]}"

# --- Sign -------------------------------------------------------------------

KEYSTORE="${PATCHWORK_KEYSTORE_FILE:-$ROOT/signing/patchwork-test.jks}"
[[ -f "$KEYSTORE" ]] || die "keystore not found: $KEYSTORE (set PATCHWORK_KEYSTORE_FILE)"
KEYSTORE="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"
STORE_PASSWORD="${PATCHWORK_STORE_PASSWORD:-patchwork123}"
KEY_ALIAS="${PATCHWORK_KEY_ALIAS:-patchwork}"
KEY_PASSWORD="${PATCHWORK_KEY_PASSWORD:-patchwork123}"

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

APKSIGNER="$(find_build_tool apksigner)" || die "apksigner not found (set ANDROID_HOME)"
AAPT="$(find_build_tool aapt)" || die "aapt not found (set ANDROID_HOME)"

echo "==> Signing $(basename "$UNSIGNED") with $(basename "$KEYSTORE")"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
SIGNED="$(mktemp "$OUT/.apk-signed.XXXXXX")"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass "pass:$STORE_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" --ks-key-alias "$KEY_ALIAS" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$SIGNED" "$UNSIGNED"

echo "==> Verifying APK signature"
"$APKSIGNER" verify --print-certs "$SIGNED"

echo "==> Verifying APK metadata"
BADGING="$("$AAPT" dump badging "$SIGNED")"
ACTUAL_PACKAGE="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='([^']*)'.*/\1/p")"
ACTUAL_CODE="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='[^']*' versionCode='([0-9]+)'.*/\1/p")"
ACTUAL_NAME="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='[^']*' versionCode='[0-9]+' versionName='([^']*)'.*/\1/p")"

[[ "$ACTUAL_PACKAGE" == "com.pengstem.baidunetgo" ]] \
    || die "APK package is '$ACTUAL_PACKAGE', expected com.pengstem.baidunetgo"
[[ "$ACTUAL_CODE" == "$NEW_CODE" ]] \
    || die "APK versionCode is '$ACTUAL_CODE', expected '$NEW_CODE'"
[[ "$ACTUAL_NAME" == "$NEW_NAME" ]] \
    || die "APK versionName is '$ACTUAL_NAME', expected '$NEW_NAME'"

# --- Output -----------------------------------------------------------------

DEST="$OUT/BaiduNetGo-$NEW_NAME.apk"
mv -f "$SIGNED" "$DEST"
echo "==> APK: $DEST"

SHA256="$(sha256sum "$DEST" | awk '{print $1}')"
APK_SIZE="$(stat -c %s "$DEST" 2>/dev/null || stat -f %z "$DEST")"
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat >"$OUT/build-info.txt" <<INFO
app=baidunetgo-android
project=BaiduNetGo Android
package=com.pengstem.baidunetgo
upstream_url=https://github.com/pengstem/baidunetgo
upstream_head=$upstream_head
upstream_commit=$upstream_commit
upstream_fallback=$upstream_fallback
patch_count=$patch_count
version_code=$ACTUAL_CODE
version_name=$ACTUAL_NAME
built_at=$BUILT_AT
apk=$(basename "$DEST")
apk_size=$APK_SIZE
sha256=$SHA256
INFO

echo "==> Build info"
cat "$OUT/build-info.txt"
echo "PATCHWORK_RESULT_app=baidunetgo-android"
echo "PATCHWORK_RESULT_project=BaiduNetGo Android"
echo "PATCHWORK_RESULT_upstream_head=$upstream_head"
echo "PATCHWORK_RESULT_upstream_commit=$upstream_commit"
echo "PATCHWORK_RESULT_upstream_fallback=$upstream_fallback"
echo "PATCHWORK_RESULT_patch_count=$patch_count"
echo "PATCHWORK_RESULT_version_code=$NEW_CODE"
echo "PATCHWORK_RESULT_version_name=$NEW_NAME"
echo "PATCHWORK_RESULT_apk=$DEST"
echo "PATCHWORK_RESULT_build_info=$OUT/build-info.txt"
