#!/usr/bin/env bash
#
# Build a patchwork app: fetch upstream, apply the patch queue, bump the
# version, then (unless --no-build) run Gradle and verify the APK.
#
# Usage:
#   scripts/build-app.sh <app> [--out DIR] [--no-build]
#
# <app> is any directory under apps/ with an app.conf (currently expenses,
# screentime and fcitx5). The list is not hard-coded here.
#
# Environment:
#   PATCHWORK_REVISION        patch revision appended after the upstream
#                             versionCode * 100 (default: number of commits
#                             that touched apps/<app>/patches, max 99)
#   PATCHWORK_KEYSTORE_FILE   signing keystore (default: signing/patchwork-test.jks)
#   PATCHWORK_STORE_PASSWORD  keystore password (default: patchwork123)
#   PATCHWORK_KEY_ALIAS       key alias (default: patchwork)
#   PATCHWORK_KEY_PASSWORD    key password (default: patchwork123)
#   ANDROID_HOME, ANDROID_USER_HOME, GRADLE_USER_HOME
#                             default to ../.toolchain/* when unset
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.cache"
MIRROR_DIR="$CACHE/mirror"
WORK_ROOT="$CACHE/work"

usage() {
    cat <<'EOF'
Usage: scripts/build-app.sh <app> [--out DIR] [--no-build]

<app> is any directory under apps/ with an app.conf (expenses, screentime,
fcitx5). Fetches the upstream branch into .cache/mirror/<app>.git, creates a
fresh checkout in .cache/work/<app>, applies apps/<app>/patches/*.patch with
`git am -3`, sets versionCode/versionName (file rewrite, or environment
variables for VERSION_STYLE=build-logic), and builds a signed release APK.

Options:
  --out DIR    copy the APK and build-info.txt here (default: dist/)
  --no-build   stop after applying patches and rewriting the version
  -h, --help   show this help

Output:
  <out>/<APK_NAME_PREFIX>-<versionName>.apk
  <out>/build-info.txt
  PATCHWORK_RESULT_* lines on stdout (stable, machine-readable)
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

APP=""
OUT="$ROOT/dist"
NO_BUILD=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --out)
            [[ $# -ge 2 ]] || die "--out needs a directory"
            OUT="$2"
            shift 2
            ;;
        --no-build)
            NO_BUILD=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        -*)
            die "unknown option: $1 (try --help)"
            ;;
        *)
            [[ -z "$APP" ]] || die "unexpected argument: $1"
            APP="$1"
            shift
            ;;
    esac
done

[[ -n "$APP" ]] || {
    usage >&2
    exit 2
}
CONF="$ROOT/apps/$APP/app.conf"
# The app list is derived from apps/*/app.conf so that adding an app needs no
# change here. Reject path tricks such as `../expenses` before touching CONF.
[[ "$APP" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid app name '$APP'"
[[ -f "$CONF" ]] || {
    known="$(find "$ROOT/apps" -mindepth 2 -maxdepth 2 -name app.conf -printf '%h\n' 2>/dev/null \
        | sed 's#.*/##' | LC_ALL=C sort | paste -sd',' - | sed 's/,/, /g')"
    die "unknown app '$APP' (expected one of: ${known:-none})"
}
# shellcheck source=/dev/null
source "$CONF"
[[ "${APP_ID:-}" == "$APP" ]] \
    || die "$CONF sets APP_ID='${APP_ID:-<unset>}', expected '$APP'"

for var in APP_ID PROJECT_NAME UPSTREAM_URL UPSTREAM_BRANCH PACKAGE \
    GRADLE_TASK APK_SUBDIR VERSION_FILE SIGNING_STYLE APK_NAME_PREFIX; do
    [[ -n "${!var:-}" ]] || die "app config $CONF does not set $var"
done

# The revision suffix keeps patch-only releases installable: every commit that
# touches this app's patch queue bumps the effective versionCode by one, even
# when the upstream version itself did not change. Callers can still pin an
# exact revision with PATCHWORK_REVISION=…
if [[ -z "${PATCHWORK_REVISION:-}" ]]; then
    PATCHWORK_REVISION="$(git -C "$ROOT" rev-list --count HEAD -- "apps/$APP/patches" 2>/dev/null || true)"
    [[ -n "$PATCHWORK_REVISION" ]] || PATCHWORK_REVISION=0
    echo "==> PATCHWORK_REVISION not set; using patch-queue commit count: $PATCHWORK_REVISION"
fi
[[ "$PATCHWORK_REVISION" =~ ^[0-9]+$ ]] \
    || die "PATCHWORK_REVISION must be a non-negative integer, got '$PATCHWORK_REVISION'"
if (( 10#$PATCHWORK_REVISION > 99 )); then
    die "PATCHWORK_REVISION=$PATCHWORK_REVISION exceeds the maximum of 99 (versionCode = upstream_code * 100 + revision, so larger revisions would collide with the next upstream release). Squash patch-queue commits or set PATCHWORK_REVISION explicitly."
fi

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"

# Keep the build clones usable even when the caller has no git identity
# configured (patch author data comes from the patches themselves).
export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-Patchwork}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-patchwork@localhost}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-Patchwork}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-patchwork@localhost}"
export GIT_TERMINAL_PROMPT=0

MIRROR="$MIRROR_DIR/$APP.git"
WORK="$WORK_ROOT/$APP"
mkdir -p "$MIRROR_DIR" "$WORK_ROOT"

if [[ -d "$MIRROR" ]]; then
    echo "==> Updating mirror $MIRROR"
    git -C "$MIRROR" remote update --prune
else
    echo "==> Cloning mirror $UPSTREAM_URL"
    git clone --mirror --quiet "$UPSTREAM_URL" "$MIRROR"
fi

UPSTREAM_SHA="$(git -C "$MIRROR" rev-parse --verify "refs/heads/$UPSTREAM_BRANCH^{commit}")"
echo "    upstream commit: $UPSTREAM_SHA ($UPSTREAM_BRANCH)"

# Always start from a fresh checkout. A dirty tree is never reused.
rm -rf "$WORK"
git clone --quiet "$MIRROR" "$WORK"
git -C "$WORK" checkout --quiet -B build "$UPSTREAM_SHA"

mapfile -t PATCHES < <(find "$ROOT/apps/$APP/patches" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)
[[ ${#PATCHES[@]} -gt 0 ]] || die "no patches found in apps/$APP/patches"

echo "==> Applying ${#PATCHES[@]} patch(es)"
for patch in "${PATCHES[@]}"; do
    if ! git -C "$WORK" am -3 "$patch"; then
        {
            echo ""
            echo "ERROR: patch '$(basename "$patch")' does not apply cleanly"
            echo "       to upstream $UPSTREAM_SHA."
            echo ""
            echo "Conflicted files:"
            git -C "$WORK" diff --name-only --diff-filter=U | sed 's/^/  /' || true
            echo ""
            echo "Worktree kept for inspection: $WORK"
            echo "Resolve the conflict there and regenerate the patch queue"
            echo "(see scripts/sync-patches.sh), then rerun this script."
        } >&2
        exit 1
    fi
done

VERSION_PATH="$WORK/$VERSION_FILE"
[[ -f "$VERSION_PATH" ]] || die "version file not found in upstream: $VERSION_FILE"

if [[ "${VERSION_STYLE:-gradle}" == "build-logic" ]]; then
    # fcitx5-android computes versionCode/versionName inside build-logic
    # (Versions.kt) instead of a Gradle file. Read the upstream constants,
    # derive the per-ABI code exactly like Versions.calculateVersionCode(),
    # then hand the patchwork values to the build through the environment
    # (see apps/fcitx5/app.conf and the PATCHWORK_REVISION patch).
    BASE_CODE_RE='^[[:space:]]*const val baseVersionCode[[:space:]]*=[[:space:]]*([0-9]+)'
    BASE_NAME_RE='^[[:space:]]*const val baseVersionName[[:space:]]*=[[:space:]]*"([^"]*)"'
    base_code="$(sed -nE "s/${BASE_CODE_RE}/\1/p" "$VERSION_PATH" | head -n 1)"
    base_name="$(sed -nE "s/${BASE_NAME_RE}/\1/p" "$VERSION_PATH" | head -n 1)"
    [[ "$base_code" =~ ^[0-9]+$ ]] || die "could not parse baseVersionCode from $VERSION_FILE"
    [[ -n "$base_name" ]] || die "could not parse baseVersionName from $VERSION_FILE"

    export BUILD_ABI="${BUILD_ABI:-arm64-v8a}"
    case "$BUILD_ABI" in
        armeabi-v7a) ABI_ID=1 ;;
        arm64-v8a) ABI_ID=2 ;;
        x86) ABI_ID=3 ;;
        x86_64) ABI_ID=4 ;;
        *) die "unsupported BUILD_ABI '$BUILD_ABI'" ;;
    esac

    UPSTREAM_CODE=$((10#$base_code * 10 + ABI_ID))
    UPSTREAM_NAME="$base_name"
    NEW_CODE=$((10#$UPSTREAM_CODE * 100 + 10#$PATCHWORK_REVISION))
    NEW_NAME="${UPSTREAM_NAME}-patchwork"

    export PATCHWORK_REVISION
    export BUILD_VERSION_NAME="$NEW_NAME"

    echo "==> Version is computed by build-logic, no file rewrite"
    echo "    ABI:         $BUILD_ABI (id $ABI_ID)"
    echo "    versionCode: $UPSTREAM_CODE -> $NEW_CODE"
    echo "    versionName: $UPSTREAM_NAME -> $NEW_NAME"
else
    CODE_RE='^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+)[[:space:]]*$'
    NAME_RE='^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)"[[:space:]]*$'

    code_matches="$(grep -cE "$CODE_RE" "$VERSION_PATH" || true)"
    [[ "$code_matches" -eq 1 ]] \
        || die "expected exactly one versionCode line in $VERSION_FILE, found $code_matches"
    name_matches="$(grep -cE "$NAME_RE" "$VERSION_PATH" || true)"
    [[ "$name_matches" -eq 1 ]] \
        || die "expected exactly one versionName line in $VERSION_FILE, found $name_matches"

    UPSTREAM_CODE="$(sed -nE "s/${CODE_RE}/\1/p" "$VERSION_PATH")"
    UPSTREAM_NAME="$(sed -nE "s/${NAME_RE}/\1/p" "$VERSION_PATH")"
    [[ "$UPSTREAM_CODE" =~ ^[0-9]+$ ]] || die "could not parse versionCode from $VERSION_FILE"
    [[ -n "$UPSTREAM_NAME" ]] || die "could not parse versionName from $VERSION_FILE"

    NEW_CODE=$((10#$UPSTREAM_CODE * 100 + 10#$PATCHWORK_REVISION))
    if [[ "$UPSTREAM_NAME" == *-patchwork ]]; then
        NEW_NAME="$UPSTREAM_NAME"
    else
        NEW_NAME="$UPSTREAM_NAME-patchwork"
    fi

    echo "==> Rewriting $VERSION_FILE"
    echo "    versionCode: $UPSTREAM_CODE -> $NEW_CODE"
    echo "    versionName: $UPSTREAM_NAME -> $NEW_NAME"

    tmp="$(mktemp "${VERSION_PATH}.XXXXXX")"
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
    ' "$VERSION_PATH" >"$tmp"
    chmod --reference="$VERSION_PATH" "$tmp"
    mv "$tmp" "$VERSION_PATH"

    grep -qE "^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*${NEW_CODE}[[:space:]]*$" "$VERSION_PATH" \
        || die "versionCode rewrite failed in $VERSION_FILE"
    grep -qF "versionName = \"$NEW_NAME\"" "$VERSION_PATH" \
        || die "versionName rewrite failed in $VERSION_FILE"
fi

print_result() {
    echo "PATCHWORK_RESULT_app=$APP"
    echo "PATCHWORK_RESULT_project=$PROJECT_NAME"
    echo "PATCHWORK_RESULT_upstream_commit=$UPSTREAM_SHA"
    echo "PATCHWORK_RESULT_patch_count=${#PATCHES[@]}"
    echo "PATCHWORK_RESULT_version_code=$NEW_CODE"
    echo "PATCHWORK_RESULT_version_name=$NEW_NAME"
    echo "PATCHWORK_RESULT_worktree=$WORK"
}

if [[ "$NO_BUILD" -eq 1 ]]; then
    echo "==> Patches applied (build skipped: --no-build)"
    print_result
    exit 0
fi

# --- Build -----------------------------------------------------------------

DEFAULT_TOOLCHAIN="$(cd "$ROOT/.." && pwd)/.toolchain"
if [[ -z "${ANDROID_HOME:-}" && -d "$DEFAULT_TOOLCHAIN/android-sdk" ]]; then
    export ANDROID_HOME="$DEFAULT_TOOLCHAIN/android-sdk"
fi
if [[ -z "${ANDROID_USER_HOME:-}" && -d "$DEFAULT_TOOLCHAIN/android-home" ]]; then
    export ANDROID_USER_HOME="$DEFAULT_TOOLCHAIN/android-home"
fi
if [[ -z "${GRADLE_USER_HOME:-}" && -d "$DEFAULT_TOOLCHAIN/gradle-home" ]]; then
    export GRADLE_USER_HOME="$DEFAULT_TOOLCHAIN/gradle-home"
fi

KEYSTORE="${PATCHWORK_KEYSTORE_FILE:-$ROOT/signing/patchwork-test.jks}"
[[ -f "$KEYSTORE" ]] || die "keystore not found: $KEYSTORE (set PATCHWORK_KEYSTORE_FILE)"
KEYSTORE="$(cd "$(dirname "$KEYSTORE")" && pwd)/$(basename "$KEYSTORE")"
STORE_PASSWORD="${PATCHWORK_STORE_PASSWORD:-patchwork123}"
KEY_ALIAS="${PATCHWORK_KEY_ALIAS:-patchwork}"
KEY_PASSWORD="${PATCHWORK_KEY_PASSWORD:-patchwork123}"

if [[ "$SIGNING_STYLE" == "expenses" ]]; then
    # Upstream reads app/keystore/android_keystore.jks and only enables the
    # release signing config when SIGNING_STORE_PASSWORD is present.
    mkdir -p "$WORK/app/keystore"
    cp "$KEYSTORE" "$WORK/app/keystore/android_keystore.jks"
    export SIGNING_STORE_PASSWORD="$STORE_PASSWORD"
    export SIGNING_KEY_ALIAS="$KEY_ALIAS"
    export SIGNING_KEY_PASSWORD="$KEY_PASSWORD"
elif [[ "$SIGNING_STYLE" == "screentime" ]]; then
    export ANDROID_KEY_STORE_FILE="$KEYSTORE"
    export ANDROID_KEY_STORE_PASSWORD="$STORE_PASSWORD"
    export ANDROID_KEY_ALIAS="$KEY_ALIAS"
    export ANDROID_KEY_PASSWORD="$KEY_PASSWORD"
elif [[ "$SIGNING_STYLE" == "fcitx5" ]]; then
    # build-logic ProjectExtensions.fromProjectEnv(): the release signing config
    # only exists when SIGN_KEY_FILE (or SIGN_KEY_BASE64) is present. Upstream
    # uses a single SIGN_KEY_PWD for both the keystore and the key.
    [[ "$KEY_PASSWORD" == "$STORE_PASSWORD" ]] \
        || die "fcitx5 signing needs PATCHWORK_KEY_PASSWORD == PATCHWORK_STORE_PASSWORD (upstream exposes one SIGN_KEY_PWD)"
    export SIGN_KEY_FILE="$KEYSTORE"
    export SIGN_KEY_PWD="$STORE_PASSWORD"
    export SIGN_KEY_ALIAS="$KEY_ALIAS"
else
    die "unknown SIGNING_STYLE '$SIGNING_STYLE' in $CONF"
fi

# fcitx5-android compiles C++ from submodules (~450 MB with the shallow
# `prebuilt`); the patch queue itself never touches them. Doing this here (and
# not before `git am`) keeps `--no-build` from downloading them. Unset for the
# Gradle-only apps.
if [[ "${SUBMODULES:-}" == "recursive" ]]; then
    echo "==> Initializing submodules (recursive)"
    git -C "$WORK" submodule update --init --recursive
fi

echo "==> Building $GRADLE_TASK"
chmod +x "$WORK/gradlew"
(
    cd "$WORK"
    ./gradlew "$GRADLE_TASK" --no-daemon --stacktrace
)

APK_DIR="$WORK/$APK_SUBDIR"
# Apps that build per-ABI split APKs (no universal APK) select the one ABI this
# run built, so a stray APK from another ABI is never picked up.
APK_FIND_NAME='*.apk'
if [[ -n "${BUILD_ABI:-}" ]]; then
    APK_FIND_NAME="*-${BUILD_ABI}-release.apk"
fi
mapfile -t APKS < <(find "$APK_DIR" -maxdepth 1 -type f -name "$APK_FIND_NAME" ! -name '*-unsigned.apk' | LC_ALL=C sort)
[[ ${#APKS[@]} -gt 0 ]] || die "build succeeded but no APK was found in $APK_SUBDIR (filter: $APK_FIND_NAME)"
APK_PATH="${APKS[0]}"

DEST="$OUT/${APK_NAME_PREFIX}-${NEW_NAME}${APK_NAME_SUFFIX:+-$APK_NAME_SUFFIX}.apk"
cp "$APK_PATH" "$DEST"
echo "==> APK: $DEST"

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

echo "==> Verifying APK signature"
"$APKSIGNER" verify --print-certs "$DEST"

echo "==> Verifying APK metadata"
BADGING="$("$AAPT" dump badging "$DEST")"
ACTUAL_PACKAGE="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='([^']*)'.*/\1/p")"
ACTUAL_CODE="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='[^']*' versionCode='([0-9]+)'.*/\1/p")"
ACTUAL_NAME="$(printf '%s\n' "$BADGING" | sed -nE "s/^package: name='[^']*' versionCode='[0-9]+' versionName='([^']*)'.*/\1/p")"

[[ "$ACTUAL_PACKAGE" == "$PACKAGE" ]] \
    || die "APK package is '$ACTUAL_PACKAGE', expected '$PACKAGE'"
[[ "$ACTUAL_CODE" == "$NEW_CODE" ]] \
    || die "APK versionCode is '$ACTUAL_CODE', expected '$NEW_CODE'"
[[ "$ACTUAL_NAME" == "$NEW_NAME" ]] \
    || die "APK versionName is '$ACTUAL_NAME', expected '$NEW_NAME'"

SHA256="$(sha256sum "$DEST" | awk '{print $1}')"
APK_SIZE="$(stat -c %s "$DEST" 2>/dev/null || stat -f %z "$DEST")"
BUILT_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat >"$OUT/build-info.txt" <<INFO
app=$APP
project=$PROJECT_NAME
package=$ACTUAL_PACKAGE
upstream_url=$UPSTREAM_URL
upstream_commit=$UPSTREAM_SHA
patch_count=${#PATCHES[@]}
version_code=$ACTUAL_CODE
version_name=$ACTUAL_NAME
variant=release
built_at=$BUILT_AT
apk=$(basename "$DEST")
apk_size=$APK_SIZE
sha256=$SHA256
INFO
# Only per-ABI builds carry an abi= line; the Gradle-only apps keep their
# historical build-info.txt exactly as before.
if [[ -n "${BUILD_ABI:-}" ]]; then
    printf 'abi=%s\n' "$BUILD_ABI" >>"$OUT/build-info.txt"
fi

echo "==> Build info"
cat "$OUT/build-info.txt"
echo "PATCHWORK_RESULT_apk=$DEST"
echo "PATCHWORK_RESULT_build_info=$OUT/build-info.txt"
print_result
