#!/usr/bin/env bash
#
# Refresh apps/*/patches/ from the local authoring clones.
#
# Usage:
#   scripts/sync-patches.sh [--check]
#
# Source repositories (override with environment variables):
#   SCREENTIME_DIR     default: <repo>/../ScreenTime
#   EXPENSES_DIR       default: <repo>/../RecurringExpenseTracker
#   FCITX5_DIR         default: <repo>/../fcitx5-android
#   FCITX5_PATCHES_DIR default: <repo>/../.toolchain/tmp/fcitx5-patches
#   FCITX5_BRANCH      default: feat/rime-sync-manager
#   FCITX5_BASE        default: master
#
# When a working tree has no .patchwork/patches (for example because the
# clone is on a feature branch), the files are read from its `patchwork`
# branch with `git archive`, so the clones themselves are never modified.
#
# fcitx5-android has no .patchwork/ directory: its queue is exported with
# `git format-patch master..feat/rime-sync-manager` from the authoring clone,
# or copied from the staged directory when that clone is not available.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREENTIME_DIR="${SCREENTIME_DIR:-$ROOT/../ScreenTime}"
EXPENSES_DIR="${EXPENSES_DIR:-$ROOT/../RecurringExpenseTracker}"
FCITX5_DIR="${FCITX5_DIR:-$ROOT/../fcitx5-android}"
FCITX5_PATCHES_DIR="${FCITX5_PATCHES_DIR:-$ROOT/../.toolchain/tmp/fcitx5-patches}"
FCITX5_BRANCH="${FCITX5_BRANCH:-feat/rime-sync-manager}"
FCITX5_BASE="${FCITX5_BASE:-master}"

usage() {
    cat <<'EOF'
Usage: scripts/sync-patches.sh [--check]

Copies the patch queues from the authoring clones into apps/*/patches/,
deleting stale *.patch files first.

Options:
  --check      report differences only; exit 1 if anything is out of sync
  -h, --help   show this help

Environment:
  SCREENTIME_DIR     ScreenTime authoring clone (default: ../ScreenTime)
  EXPENSES_DIR       RecurringExpenseTracker clone (default: ../RecurringExpenseTracker)
  FCITX5_DIR         fcitx5-android clone (default: ../fcitx5-android)
  FCITX5_PATCHES_DIR staged fcitx5 queue used when the clone is missing
                     (default: ../.toolchain/tmp/fcitx5-patches)
  FCITX5_BRANCH      feature branch holding the fcitx5 queue
                     (default: feat/rime-sync-manager)
  FCITX5_BASE        upstream base of that branch (default: master)
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

CHECK=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --check)
            CHECK=1
            shift
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

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

resolve_source_patches() {
    local clone="$1" tmp="$2" source="$1/.patchwork/patches"
    if [[ -d "$source" ]]; then
        printf '%s\n' "$source"
        return 0
    fi
    if git -C "$clone" rev-parse --verify --quiet patchwork >/dev/null 2>&1; then
        mkdir -p "$tmp"
        git -C "$clone" archive patchwork .patchwork/patches | tar -x -C "$tmp"
        printf '%s\n' "$tmp/.patchwork/patches"
        return 0
    fi
    return 1
}

# fcitx5-android keeps its queue on a feature branch instead of a
# .patchwork/ directory, so export it with git format-patch (same flags as
# the original export) and fall back to the staged copy in .toolchain/tmp.
resolve_fcitx5_patches() {
    local tmp="$1"
    if [[ -d "$FCITX5_DIR/.patchwork/patches" ]]; then
        printf '%s\n' "$FCITX5_DIR/.patchwork/patches"
        return 0
    fi
    if [[ -d "$FCITX5_DIR" ]] \
        && git -C "$FCITX5_DIR" rev-parse --verify --quiet "refs/heads/$FCITX5_BRANCH" >/dev/null 2>&1; then
        mkdir -p "$tmp"
        git -C "$FCITX5_DIR" format-patch --no-signature --zero-commit --no-numbered \
            "$FCITX5_BASE..$FCITX5_BRANCH" -o "$tmp" >/dev/null
        printf '%s\n' "$tmp"
        return 0
    fi
    if [[ -d "$FCITX5_PATCHES_DIR" ]]; then
        printf '%s\n' "$FCITX5_PATCHES_DIR"
        return 0
    fi
    return 1
}

status=0

sync_one() {
    local app="$1" src="$2" dest="$ROOT/apps/$1/patches"
    local -a patches=()

    mapfile -t patches < <(find "$src" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)
    [[ ${#patches[@]} -gt 0 ]] || die "no patches found in $src"

    mkdir -p "$dest"

    if [[ "$CHECK" -eq 1 ]]; then
        local p base found=0
        local -a dest_files=()
        mapfile -t dest_files < <(find "$dest" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)

        for p in "${patches[@]}"; do
            base="$(basename "$p")"
            if [[ ! -f "$dest/$base" ]]; then
                echo "MISSING  apps/$app/patches/$base" >&2
                status=1
            elif ! cmp -s "$p" "$dest/$base"; then
                echo "DIFF     apps/$app/patches/$base" >&2
                status=1
            else
                found=$((found + 1))
            fi
        done
        for p in "${dest_files[@]}"; do
            base="$(basename "$p")"
            if [[ ! -f "$src/$base" ]]; then
                echo "STALE    apps/$app/patches/$base" >&2
                status=1
            fi
        done
        echo "$app: ${#patches[@]} upstream patch(es), $found identical" >&2
        return 0
    fi

    rm -f "$dest"/*.patch
    cp -f "${patches[@]}" "$dest/"
    echo "==> $app: copied ${#patches[@]} patch(es) to apps/$app/patches"
}

[[ -d "$SCREENTIME_DIR" ]] || die "authoring clone not found: $SCREENTIME_DIR"
src="$(resolve_source_patches "$SCREENTIME_DIR" "$TMP_DIR/screentime")" \
    || die "no .patchwork/patches in $SCREENTIME_DIR (and no patchwork branch)"
sync_one screentime "$src"

[[ -d "$EXPENSES_DIR" ]] || die "authoring clone not found: $EXPENSES_DIR"
src="$(resolve_source_patches "$EXPENSES_DIR" "$TMP_DIR/expenses")" \
    || die "no .patchwork/patches in $EXPENSES_DIR (and no patchwork branch)"
sync_one expenses "$src"

src="$(resolve_fcitx5_patches "$TMP_DIR/fcitx5")" \
    || die "no fcitx5 queue: neither $FCITX5_DIR ($FCITX5_BRANCH) nor $FCITX5_PATCHES_DIR"
sync_one fcitx5 "$src"

if [[ "$CHECK" -eq 1 ]]; then
    if [[ "$status" -eq 0 ]]; then
        echo "Patch queues are in sync." >&2
    else
        echo "Patch queues differ; run scripts/sync-patches.sh to update." >&2
    fi
fi
exit "$status"
