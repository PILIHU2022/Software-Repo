#!/usr/bin/env bash
#
# Build BaiduNetGo (desktop) on top of its upstream branch, patch queue included.
#
# Usage:
#   scripts/build-baidunetgo.sh [--prepare] [--build] [--out DIR]
#
# --prepare  mirror-fetch upstream, check out the upstream head and rebase the
#            queue in desktop/baidunetgo/patches onto it (`git am -3`), then
#            stop. The workflow runs this first so actions/setup-go can read the
#            patched tree's go.mod.
# --build    build the already prepared tree. Default (no flag): both phases.
# --out DIR  where the installers and build-info.txt are copied
#            (default: dist/baidunetgo).
#
# Environment:
#   PATCHWORK_PLATFORMS       override BUILD_PLATFORMS from app.conf
#   PATCHWORK_STATE_FILE      last successfully built upstream commits, used as
#                             the fallback when the queue no longer applies
#                             (default: desktop/.build-state.json)
#   PATCHWORK_NET_RETRIES     network retry count for the mirror fetch (default 3)
#   PATCHWORK_FEED_URL        update feed linked into the binaries as their
#                             default feed, via
#                             -X …/internal/updates.defaultFeed=<url>
#                             (default: UPDATE_FEED_URL from app.conf; empty
#                             leaves the binaries without a built-in feed)
#
# BaiduNetGo is not an Android app: nothing here runs Gradle, touches
# fdroid/ or publishes through F-Droid. The build is x86 only — linux/amd64
# and windows/amd64 — and MyGo needs no cgo, so both come from one x86 runner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.cache"
MIRROR_DIR="$CACHE/mirror"
WORK_ROOT="$CACHE/work"
APP="baidunetgo"
CONF="$ROOT/desktop/$APP/app.conf"

usage() {
    # Print the header comment only: stop at the first non-comment line, so
    # documenting another variable above never spills code into --help.
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

MODE="all"
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
for var in PROJECT_NAME UPSTREAM_URL UPSTREAM_BRANCH BUILD_PLATFORMS; do
    [[ -n "${!var:-}" ]] || die "app config $CONF does not set $var"
done

PLATFORMS="${PATCHWORK_PLATFORMS:-$BUILD_PLATFORMS}"
STATE_FILE="${PATCHWORK_STATE_FILE:-$ROOT/${STATE_FILE:-desktop/.build-state.json}}"
# The default update feed linked into the binaries. UPDATE_FEED_URL is allowed
# to be unset, so an app.conf that predates the feed still builds: without it
# nothing is linked and a binary only checks a feed the user enters in the
# settings. PATCHWORK_FEED_URL wins, because CI points it at github.repository.
FEED_URL="${PATCHWORK_FEED_URL:-${UPDATE_FEED_URL:-}}"
PATCHES_DIR="$ROOT/desktop/$APP/patches"
MIRROR="$MIRROR_DIR/$APP.git"
WORK="$WORK_ROOT/$APP"
PREPARED="$WORK/.patchwork-prepared"

# x86 only: refuse to build anything else even if a config or env var is
# edited by mistake. The workflow's artifact names promise x86 binaries.
for target in ${PLATFORMS//,/ }; do
    case "$target" in
        linux/amd64|windows/amd64) ;;
        *) die "unsupported platform '$target'; this app is x86 only (linux/amd64, windows/amd64)" ;;
    esac
done

export GIT_AUTHOR_NAME="${GIT_AUTHOR_NAME:-Patchwork}"
export GIT_AUTHOR_EMAIL="${GIT_AUTHOR_EMAIL:-patchwork@localhost}"
export GIT_COMMITTER_NAME="${GIT_COMMITTER_NAME:-Patchwork}"
export GIT_COMMITTER_EMAIL="${GIT_COMMITTER_EMAIL:-patchwork@localhost}"
export GIT_TERMINAL_PROMPT=0

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

sync_mirror() {
    if [[ -d "$MIRROR" ]]; then
        git -C "$MIRROR" remote update --prune
    else
        git clone --mirror --quiet "$UPSTREAM_URL" "$MIRROR"
    fi
}

# ---------------------------------------------------------------- prepare ----

prepare() {
    mkdir -p "$MIRROR_DIR" "$WORK_ROOT"

    echo "==> Updating mirror $MIRROR"
    if ! retry_network sync_mirror; then
        rm -rf "$MIRROR"
        die "could not fetch $UPSTREAM_URL"
    fi

    local upstream_head
    upstream_head="$(git -C "$MIRROR" rev-parse --verify "refs/heads/$UPSTREAM_BRANCH^{commit}")"
    echo "    upstream commit: $upstream_head ($UPSTREAM_BRANCH)"

    rm -rf "$WORK"
    git clone --quiet "$MIRROR" "$WORK"
    git -C "$WORK" checkout --quiet -B build "$upstream_head"

    mapfile -t patches < <(find "$PATCHES_DIR" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)
    [[ ${#patches[@]} -gt 0 ]] || die "no patches found in desktop/$APP/patches"

    local upstream_sha="$upstream_head" fallback_used=0 patch
    echo "==> Rebasing ${#patches[@]} patch(es) onto $upstream_head"
    if ! apply_queue "${patches[@]}"; then
        local recorded
        recorded="$(recorded_upstream_sha)"
        if [[ ! "$recorded" =~ ^[0-9a-f]{40}$ ]]; then
            echo "WARNING: no usable previous upstream commit in $STATE_FILE; no fallback attempted" >&2
            exit 1
        fi
        if [[ "$recorded" == "$upstream_head" ]]; then
            echo "WARNING: recorded upstream commit equals the head; no fallback attempted" >&2
            exit 1
        fi
        if ! ensure_commit_available "$recorded"; then
            echo "WARNING: recorded commit $recorded is not available in $MIRROR; no fallback attempted" >&2
            exit 1
        fi
        echo ""
        echo "!! FALLBACK: the queue does not apply to upstream $upstream_head"
        echo "!! FALLBACK: retrying once on the last successfully built commit $recorded"
        fallback_used=1
        upstream_sha="$recorded"
        git -C "$WORK" am --abort >/dev/null 2>&1 || true
        git -C "$WORK" checkout --quiet --force -B build "$upstream_sha" \
            || die "could not check out fallback commit $upstream_sha"
        git -C "$WORK" clean -qfdx
        if ! apply_queue "${patches[@]}"; then
            echo "ERROR: the fallback commit $upstream_sha also rejects the queue." >&2
            echo "       Re-anchor desktop/$APP/patches against $upstream_head." >&2
            exit 1
        fi
        echo "!! FALLBACK: queue applied on $upstream_sha; upstream $upstream_head is NOT included"
    fi

    {
        echo "upstream_head=$upstream_head"
        echo "upstream_commit=$upstream_sha"
        echo "upstream_fallback=$fallback_used"
        echo "patch_count=${#patches[@]}"
    } >"$PREPARED"

    echo "==> Prepared $WORK"
    print_result "$upstream_head" "$upstream_sha" "$fallback_used" "${#patches[@]}"
}

apply_queue() {
    local patch
    for patch in "$@"; do
        if git -C "$WORK" am -q -3 "$patch"; then
            continue
        fi
        describe_failure "$patch"
        return 1
    done
    return 0
}

describe_failure() {
    local patch="$1"
    local subject conflicted
    subject="$(git -C "$WORK" am --show-current-patch=raw 2>/dev/null \
        | sed -n 's/^Subject: //p' | head -n 1 || true)"
    mapfile -t conflicted < <(git -C "$WORK" diff --name-only --diff-filter=U)
    {
        echo ""
        echo "ERROR: patch '$(basename "$patch")' does not apply cleanly."
        if [[ -n "$subject" ]]; then
            echo "       Current patch: $subject"
        fi
        echo "       Conflicted files:"
        if (( ${#conflicted[@]} > 0 )); then
            printf '         %s\n' "${conflicted[@]}"
        else
            echo "         (none reported by git am)"
        fi
        echo "       Worktree kept for inspection: $WORK"
    } >&2
}

recorded_upstream_sha() {
    [[ -f "$STATE_FILE" ]] || return 0
    command -v jq >/dev/null 2>&1 || {
        echo "WARNING: jq not found; cannot read $STATE_FILE for a fallback" >&2
        return 0
    }
    jq -r --arg app "$APP" '.[$app] // empty' "$STATE_FILE" 2>/dev/null || true
}

ensure_commit_available() {
    local sha="$1" tmp_ref="refs/patchwork/fallback-$$"
    git -C "$WORK" cat-file -e "${sha}^{commit}" 2>/dev/null && return 0
    git -C "$MIRROR" cat-file -e "${sha}^{commit}" 2>/dev/null || return 1
    git -C "$MIRROR" update-ref "$tmp_ref" "$sha" || return 1
    if ! git -C "$WORK" fetch --quiet "$MIRROR" "+${tmp_ref}:${tmp_ref}" 2>/dev/null; then
        git -C "$MIRROR" update-ref -d "$tmp_ref" || true
        return 1
    fi
    git -C "$MIRROR" update-ref -d "$tmp_ref" || true
    git -C "$WORK" update-ref -d "$tmp_ref" || true
    git -C "$WORK" cat-file -e "${sha}^{commit}" 2>/dev/null
}

# ------------------------------------------------------------------ build ----

build() {
    [[ -f "$PREPARED" ]] || die "no prepared tree; run scripts/build-baidunetgo.sh --prepare first"
    local upstream_head upstream_commit upstream_fallback patch_count
    # shellcheck source=/dev/null
    source "$PREPARED"

    command -v go >/dev/null 2>&1 || die "go not found on PATH"

    if [[ ",$PLATFORMS," == *windows/* ]] && ! command -v makensis >/dev/null 2>&1; then
        die "windows/amd64 builds need NSIS (makensis); install it (apt install nsis) or set PATCHWORK_PLATFORMS=linux/amd64"
    fi

    mkdir -p "$OUT"
    OUT="$(cd "$OUT" && pwd)"
    rm -rf "$WORK/build"

    if [[ -n "$FEED_URL" ]]; then
        # MYGO_LDFLAGS is appended to mygo's own -ldflags (mygo adds the
        # separating space itself), so a -X here reaches the build. Append
        # rather than assign: the caller or CI may pass flags of its own.
        local feed_ldflag
        feed_ldflag="-X github.com/pengstem/baidunetgo/internal/updates.defaultFeed=$FEED_URL"
        export MYGO_LDFLAGS="${MYGO_LDFLAGS:+$MYGO_LDFLAGS }$feed_ldflag"
        echo "==> linking the default update feed: $FEED_URL"
    fi

    echo "==> Building $PROJECT_NAME $PLATFORMS"
    (
        cd "$WORK"
        CGO_ENABLED=0 go tool mygo build -o "$WORK/build" -platform "$PLATFORMS"
    )

    local -a binaries=("$WORK/build/linux-amd64/baidunetgo" "$WORK/build/windows-amd64/BaiduNetGo.exe")
    local binary
    for binary in "${binaries[@]}"; do
        [[ -f "$binary" ]] || continue
        if command -v file >/dev/null 2>&1; then
            file "$binary" | grep -q 'x86-64' \
                || die "$(basename "$binary") is not an x86-64 binary: $(file -b "$binary")"
        fi
    done

    if [[ -n "$FEED_URL" ]]; then
        # -X silently ignores a symbol that no longer exists, so prove the
        # address really landed in the binary. A miss means upstream moved
        # internal/updates and the injected feed would be dead weight.
        local linux_bin="$WORK/build/linux-amd64/baidunetgo"
        [[ -f "$linux_bin" ]] || die "missing $linux_bin; cannot verify the linked update feed"
        # -a because the binary is not text; -F because the URL has no regex.
        grep -qaF -- "$FEED_URL" "$linux_bin" \
            || die "the default update feed '$FEED_URL' is not linked into $linux_bin: -X ignores an unknown symbol silently, so upstream may have moved internal/updates (updates.defaultFeed); update the -X path in this script"
    fi

    local version platform dir
    version="$(sed -n 's/^[[:space:]]*"version":[[:space:]]*"\([^"]*\)".*/\1/p' "$WORK/mygo.json" | head -n 1)"

    shopt -s nullglob
    for dir in "$WORK"/build/*/; do
        platform="$(basename "$dir")"
        case "$platform" in
            linux-amd64)
                for file in "$dir"/*.deb "$dir"/*.tar.gz; do
                    cp -f "$file" "$OUT/"
                done
                ;;
            windows-amd64)
                for file in "$dir"/*-setup-*.exe; do
                    cp -f "$file" "$OUT/"
                done
                # Portable zip, like the upstream release workflow: run without
                # installing (the setup exe is shipped separately above).
                if command -v zip >/dev/null 2>&1; then
                    (cd "$dir" && zip -qr "$OUT/BaiduNetGo-$version-windows-amd64.zip" . -x '*-setup-*.exe')
                else
                    echo "WARNING: zip not found; skipping the Windows portable zip" >&2
                fi
                ;;
        esac
    done
    shopt -u nullglob

    local built_at
    built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    {
        echo "app=$APP"
        echo "project=$PROJECT_NAME"
        echo "kind=go-desktop"
        echo "upstream_url=$UPSTREAM_URL"
        echo "upstream_head=$upstream_head"
        echo "upstream_commit=$upstream_commit"
        echo "upstream_fallback=$upstream_fallback"
        echo "patch_count=$patch_count"
        echo "version=$version"
        echo "platforms=$PLATFORMS"
        echo "update_feed=$FEED_URL"
        echo "feed_file=desktop/baidunetgo/update.json"
        echo "built_at=$built_at"
        local file
        shopt -s nullglob
        for file in "$OUT"/*.deb "$OUT"/*.tar.gz "$OUT"/*.exe "$OUT"/*.zip; do
            printf 'file=%s size=%s sha256=%s\n' \
                "$(basename "$file")" \
                "$(stat -c %s "$file" 2>/dev/null || stat -f %z "$file")" \
                "$(sha256sum "$file" | awk '{print $1}')"
        done
        shopt -u nullglob
    } >"$OUT/build-info.txt"

    echo "==> Files in $OUT"
    ls -la "$OUT"
    echo "==> Build info"
    cat "$OUT/build-info.txt"
    echo "PATCHWORK_RESULT_out=$OUT"
    echo "PATCHWORK_RESULT_version=$version"
    print_result "$upstream_head" "$upstream_commit" "$upstream_fallback" "$patch_count"
}

print_result() {
    local upstream_head="$1" upstream_commit="$2" upstream_fallback="$3" patch_count="$4"
    echo "PATCHWORK_RESULT_app=$APP"
    echo "PATCHWORK_RESULT_project=$PROJECT_NAME"
    echo "PATCHWORK_RESULT_kind=go-desktop"
    echo "PATCHWORK_RESULT_upstream_head=$upstream_head"
    echo "PATCHWORK_RESULT_upstream_commit=$upstream_commit"
    echo "PATCHWORK_RESULT_upstream_fallback=$upstream_fallback"
    echo "PATCHWORK_RESULT_patch_count=$patch_count"
    echo "PATCHWORK_RESULT_platforms=$PLATFORMS"
    echo "PATCHWORK_RESULT_update_feed=$FEED_URL"
    if [[ -d "$WORK" ]]; then
        echo "PATCHWORK_RESULT_worktree=$WORK"
    fi
}

case "$MODE" in
    prepare) prepare ;;
    build) build ;;
    all)
        prepare
        build
        ;;
esac