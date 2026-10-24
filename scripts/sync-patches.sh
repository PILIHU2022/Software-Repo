#!/usr/bin/env bash
#
# Refresh apps/*/patches/ from the local authoring clones.
#
# Usage:
#   scripts/sync-patches.sh [--check]
#
# Source repositories (override with environment variables):
#   SCREENTIME_DIR  default: <repo>/../ScreenTime
#   EXPENSES_DIR    default: <repo>/../RecurringExpenseTracker
#
# When a working tree has no .patchwork/patches (for example because the
# clone is on a feature branch), the files are read from its `patchwork`
# branch with `git archive`, so the clones themselves are never modified.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCREENTIME_DIR="${SCREENTIME_DIR:-$ROOT/../ScreenTime}"
EXPENSES_DIR="${EXPENSES_DIR:-$ROOT/../RecurringExpenseTracker}"

usage() {
    cat <<'EOF'
Usage: scripts/sync-patches.sh [--check]

Copies the patch queues from the authoring clones into apps/*/patches/,
deleting stale *.patch files first.

Options:
  --check      report differences only; exit 1 if anything is out of sync
  -h, --help   show this help

Environment:
  SCREENTIME_DIR  ScreenTime authoring clone (default: ../ScreenTime)
  EXPENSES_DIR    RecurringExpenseTracker clone (default: ../RecurringExpenseTracker)
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

status=0

sync_one() {
    local app="$1" clone="$2" dest="$ROOT/apps/$1/patches"
    local tmp="$TMP_DIR/$app" src
    local -a patches=()

    [[ -d "$clone" ]] || die "authoring clone not found: $clone"
    src="$(resolve_source_patches "$clone" "$tmp")" \
        || die "no .patchwork/patches in $clone (and no patchwork branch)"
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

sync_one screentime "$SCREENTIME_DIR"
sync_one expenses "$EXPENSES_DIR"

if [[ "$CHECK" -eq 1 ]]; then
    if [[ "$status" -eq 0 ]]; then
        echo "Patch queues are in sync." >&2
    else
        echo "Patch queues differ; run scripts/sync-patches.sh to update." >&2
    fi
fi
exit "$status"
