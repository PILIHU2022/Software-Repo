#!/usr/bin/env bash
#
# Build the Bitwarden clients with the patchwork security check and Wayland
# support. Bitwarden is not an Android app: this script never touches Gradle,
# the Android SDK or fdroid/.
#
# Usage:
#   scripts/build-bitwarden.sh --prepare
#   scripts/build-bitwarden.sh --build --target browser|desktop-linux|desktop-windows|desktop-macos [--out DIR]
#
# --prepare  clone upstream, apply desktop/bitwarden/patches with `git am -3`,
#            then install the JavaScript dependencies with `npm ci`.
# --build    build a prepared tree. `browser` builds Chrome, Firefox and Edge
#            packages; the desktop targets create the native installers.
# --target   one of the targets above (default: browser plus the host desktop)
# --out      where artifacts and build-info.txt are copied
#            (default: dist/bitwarden)
#
# Environment:
#   PATCHWORK_STATE_FILE   previously built upstream commits, used as the
#                          fallback when the queue no longer applies
#                          (default: desktop/.build-state.json)
#   PATCHWORK_NET_RETRIES  network retry count (default 3)
#   PATCHWORK_SKIP_NPM_CI  set to 1 to keep an existing node_modules while
#                          iterating locally
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="bitwarden"
CONF="$ROOT/desktop/$APP/app.conf"
WORK="$ROOT/.cache/work/$APP"
PATCHES_DIR="$ROOT/desktop/$APP/patches"
PREPARED="$WORK/.patchwork-prepared"

usage() {
    # Print the header comment only.
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

MODE="all"
TARGET=""
OUT="$ROOT/dist/$APP"
while [[ $# -gt 0 ]]; do
    case "$1" in
        --prepare)
            MODE="prepare"
            shift
            ;;
        --build)
            MODE="build"
            shift
            ;;
        --target)
            [[ $# -ge 2 ]] || die "--target needs a value"
            TARGET="$2"
            shift 2
            ;;
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

[[ -f "$CONF" ]] || die "app config not found: $CONF"
# shellcheck source=/dev/null
source "$CONF"
[[ "${APP_ID:-}" == "$APP" ]] || die "$CONF sets APP_ID='${APP_ID:-<unset>}', expected '$APP'"
for var in PROJECT_NAME UPSTREAM_URL UPSTREAM_BRANCH BROWSER_TARGETS DESKTOP_TARGETS; do
    [[ -n "${!var:-}" ]] || die "app config $CONF does not set $var"
done

mkdir -p "$ROOT/.cache/work" "$OUT"
OUT="$(cd "$OUT" && pwd)"

export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-Patchwork}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-patchwork@localhost}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-Patchwork}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-patchwork@localhost}"
export GIT_TERMINAL_PROMPT=0
export PATCHWORK_REVISION="${PATCHWORK_REVISION:-$(find "$PATCHES_DIR" -type f -name '*.patch' | wc -l | tr -d ' ')}"

retry_network() {
    local attempt=1 max="${PATCHWORK_NET_RETRIES:-3}" delay=5
    until "$@"; do
        if (( attempt >= max )); then
            echo "ERROR: network step failed after ${attempt} attempt(s): $*" >&2
            return 1
        fi
        echo "    network attempt ${attempt}/${max} failed; retrying in ${delay}s" >&2
        sleep "$delay"
        attempt=$((attempt + 1))
        delay=$((delay * 2))
    done
}

has_script() {
    local package_dir="$1" script="$2"
    node -e '
        const p = require("./" + process.argv[1] + "/package.json");
        process.exit(p.scripts && p.scripts[process.argv[2]] ? 0 : 1);
    ' "$package_dir" "$script"
}

package_name() {
    node -p 'require("./" + process.argv[1] + "/package.json").name' "$1"
}

package_version() {
    node -p 'require("./" + process.argv[1] + "/package.json").version' "$1"
}

apply_queue() {
    local patch
    local -a patches=()
    while IFS= read -r patch; do
        patches+=("$patch")
    done < <(find "$PATCHES_DIR" -type f -name '*.patch' | LC_ALL=C sort)
    [[ ${#patches[@]} -gt 0 ]] || die "no patches found in desktop/$APP/patches"

    for patch in "${patches[@]}"; do
        echo "==> Applying $(basename "$patch")"
        if ! git -C "$WORK" am -q -3 "$patch"; then
            git -C "$WORK" am --abort >/dev/null 2>&1 || true
            echo "ERROR: $(basename "$patch") does not apply to the current tree." >&2
            echo "       Re-anchor desktop/$APP/patches against the new upstream head." >&2
            return 1
        fi
    done
}

checkout_upstream() {
    local sha="$1"
    if ! git -C "$WORK" cat-file -e "$sha^{commit}" 2>/dev/null; then
        retry_network git -C "$WORK" fetch --quiet origin "$sha" \
            || die "could not fetch upstream commit $sha"
    fi
    git -C "$WORK" checkout --quiet --force -B build "$sha"
    git -C "$WORK" clean -qfdx
}

prepare() {
    rm -rf "$WORK"
    echo "==> Cloning $UPSTREAM_URL ($UPSTREAM_BRANCH)"
    retry_network git clone --quiet --filter=blob:none --single-branch \
        --branch "$UPSTREAM_BRANCH" --no-checkout "$UPSTREAM_URL" "$WORK" \
        || die "could not clone $UPSTREAM_URL"

    local upstream_head upstream_sha fallback_used=0
    upstream_head="$(git -C "$WORK" rev-parse --verify "refs/remotes/origin/$UPSTREAM_BRANCH^{commit}")"
    upstream_sha="$upstream_head"
    echo "    upstream commit: $upstream_head ($UPSTREAM_BRANCH)"

    checkout_upstream "$upstream_head"

    if ! apply_queue; then
        local recorded
        recorded="$(node -e '
            try {
                const state = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8"));
                process.stdout.write(state[process.argv[2]] || "");
            } catch {}
        ' "$ROOT/${STATE_FILE:-desktop/.build-state.json}" "$APP" 2>/dev/null || true)"
        [[ "$recorded" =~ ^[0-9a-f]{40}$ ]] \
            || die "no usable fallback commit in $ROOT/${STATE_FILE:-desktop/.build-state.json}"
        [[ "$recorded" != "$upstream_head" ]] \
            || die "the fallback commit equals the upstream head"

        echo "!! FALLBACK: retrying on the last successfully built commit $recorded"
        fallback_used=1
        upstream_sha="$recorded"
        checkout_upstream "$recorded"
        apply_queue || die "the queue also rejects the fallback commit $recorded"
    fi

    if [[ "${PATCHWORK_SKIP_NPM_CI:-0}" != "1" ]]; then
        echo "==> Installing JavaScript dependencies (node $(node --version), npm $(npm --version))"
        # Upstream's package-lock.json is not always in sync with package.json
        # for the npm bundled with the runner's Node (npm 10 reports missing
        # optional entries), so `npm ci` can abort with EUSAGE. Fall back to
        # `npm install`, which reconciles the lock instead of refusing to run.
        if ! (cd "$WORK" && npm ci --no-audit --no-fund); then
            echo "!! npm ci rejected the lockfile; falling back to npm install" >&2
            (cd "$WORK" && npm install --no-audit --no-fund)
        fi
    fi

    local version patch_count
    version="$(cd "$WORK" && package_version apps/desktop)"
    patch_count="${PATCHWORK_REVISION:-0}"
    {
        echo "upstream_head=$upstream_head"
        echo "upstream_commit=$upstream_sha"
        echo "upstream_fallback=$fallback_used"
        echo "patch_count=$patch_count"
        echo "version=$version"
    } >"$PREPARED"

    echo "==> Prepared $WORK"
}

require_prepared() {
    [[ -f "$PREPARED" ]] || die "the Bitwarden tree is not prepared; run --prepare first"
    # shellcheck disable=SC1090
    source "$PREPARED"
}

build_info() {
    local target="$1"
    {
        echo "app=$APP"
        echo "project=$PROJECT_NAME"
        echo "target=$target"
        echo "upstream_branch=$UPSTREAM_BRANCH"
        echo "upstream_head=$upstream_head"
        echo "upstream_commit=$upstream_commit"
        echo "upstream_fallback=$upstream_fallback"
        echo "patch_count=$patch_count"
        echo "version=$version"
        echo "patchwork_version=${version}+patchwork.${patch_count}"
        echo "linux_wayland=ozone-auto"
        echo "security_check=weak-reused-breach-totp"
    } >"$OUT/build-info.txt"
}

copy_desktop_artifacts() {
    local target="$1" file base
    local -a matches=()
    while IFS= read -r file; do
        matches+=("$file")
    done < <(
        find "$WORK/apps/desktop/dist" -type f \
            \( -name '*.exe' -o -name '*.msi' -o -name '*.dmg' \
               -o -name '*.AppImage' -o -name '*.deb' -o -name '*.rpm' \
               -o -name '*.tar.gz' -o -name '*.zip' \) \
            ! -name '*.blockmap' 2>/dev/null | LC_ALL=C sort
    )
    (( ${#matches[@]} > 0 )) \
        || die "no $target installer found under $WORK/apps/desktop/dist"
    for file in "${matches[@]}"; do
        base="$(basename "$file")"
        cp "$file" "$OUT/$base"
        echo "    $base"
    done
}

package_browser() {
    local variant="$1"
    local workspace zip_file manifest_dir
    workspace="$(cd "$WORK" && package_name apps/browser)"
    echo "==> Building browser:$variant"

    if (cd "$WORK" && has_script apps/browser "dist:$variant"); then
        (cd "$WORK" && npm run "dist:$variant" --workspace "$workspace")
    elif (cd "$WORK" && has_script apps/browser "build:prod:$variant"); then
        (cd "$WORK" && npm run "build:prod:$variant" --workspace "$workspace")
    elif (cd "$WORK" && has_script apps/browser "build:$variant"); then
        (cd "$WORK" && npm run "build:$variant" --workspace "$workspace")
    else
        die "apps/browser has no dist/build script for '$variant'"
    fi

    zip_file="$(find "$WORK/apps/browser" -path '*/node_modules' -prune -o -type f \
        \( -name "dist-$variant.zip" -o -name "*-$variant.zip" \
           -o -name "$variant.zip" \) -print \
        2>/dev/null | LC_ALL=C sort | head -n 1 || true)"
    if [[ -n "$zip_file" ]]; then
        cp "$zip_file" "$OUT/bitwarden-browser-$variant-${version}-patchwork.${patch_count}.zip"
        return
    fi

    manifest_dir="$(find "$WORK/apps/browser/dist" -type f -name manifest.json \
        2>/dev/null | sed 's#/manifest.json$##' | LC_ALL=C sort | head -n 1 || true)"
    [[ -n "$manifest_dir" ]] \
        || die "no browser zip or manifest found for $variant"
    (
        cd "$manifest_dir"
        zip -qr "$OUT/bitwarden-browser-$variant-${version}-patchwork.${patch_count}.zip" .
    )
}

build_browser_targets() {
    local variant
    for variant in $BROWSER_TARGETS; do
        package_browser "$variant"
    done
}

build_desktop() {
    local target="$1" platform workspace
    [[ " $DESKTOP_TARGETS " == *" ${target#desktop-} "* ]] \
        || die "unsupported desktop target '$target' (DESKTOP_TARGETS='$DESKTOP_TARGETS')"
    case "$target" in
        desktop-linux) platform="linux" ;;
        desktop-windows) platform="win" ;;
        desktop-macos) platform="mac" ;;
        *) die "unknown desktop target '$target'" ;;
    esac

    workspace="$(cd "$WORK" && package_name apps/desktop)"
    echo "==> Building $target"

    if [[ "$target" == "desktop-macos" ]] \
        && (cd "$WORK" && has_script apps/desktop build-native-macos); then
        (cd "$WORK" && npm run build-native-macos --workspace "$workspace")
    elif (cd "$WORK" && has_script apps/desktop build-native); then
        (cd "$WORK" && npm run build-native --workspace "$workspace")
    fi
    (cd "$WORK" && npm run build --workspace "$workspace")

    if (cd "$WORK" && has_script apps/desktop "dist:$platform"); then
        (cd "$WORK" && npm run "dist:$platform" --workspace "$workspace")
    else
        (cd "$WORK/apps/desktop" && "$WORK/node_modules/.bin/electron-builder" \
            "--$platform" --publish never)
    fi
    copy_desktop_artifacts "$target"
}

host_target() {
    case "$(uname -s)" in
        Linux) echo "desktop-linux" ;;
        Darwin) echo "desktop-macos" ;;
        MINGW*|MSYS*|CYGWIN*) echo "desktop-windows" ;;
        *) die "unsupported host platform: $(uname -s)" ;;
    esac
}

if [[ "$MODE" == "prepare" ]]; then
    prepare
    exit 0
fi

if [[ "$MODE" == "build" ]]; then
    require_prepared
else
    prepare
    require_prepared
fi

build_info "${TARGET:-all}"

if [[ -z "$TARGET" ]]; then
    build_browser_targets
    build_desktop "$(host_target)"
elif [[ "$TARGET" == "browser" ]]; then
    build_browser_targets
elif [[ "$TARGET" == desktop-* ]]; then
    build_desktop "$TARGET"
else
    die "unknown target '$TARGET'"
fi

echo "==> Artifacts in $OUT"
find "$OUT" -type f | sed 's#.*/##' | LC_ALL=C sort | sed 's/^/    /'

