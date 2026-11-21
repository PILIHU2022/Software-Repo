#!/usr/bin/env bash
#
# Re-anchor apps/<app>/patches on the current upstream head.
#
# Usage:
#   scripts/rebase-queue.sh <app> [--check] [--record-commit] [--message FILE]
#
# The queue is the source of truth, so the rebase is done by applying it to the
# new upstream with `scripts/build-app.sh <app> --no-build`: that fetches the
# upstream head and runs `git am -3` for every patch, which is a three-way
# rebase, and it auto-merges append-only resource-strings conflicts the same way
# a normal build does. The resulting commits are then exported back into
# apps/<app>/patches with stable headers, and the anchor commit is written to
# apps/<app>/upstream.txt.
#
# --check   only report whether the stored queue is already anchored on the
#           upstream head; exit 1 when a rebase would change files.
#
# Nothing is committed here: the caller (CI or a human) reviews and commits.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
    sed -n '3,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() { echo "ERROR: $*" >&2; exit 1; }

[[ $# -ge 1 ]] || { usage >&2; exit 2; }
APP="$1"; shift || true
CHECK=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --check) CHECK=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown argument: $1 (try --help)" ;;
    esac
done

CONF="$ROOT/apps/$APP/app.conf"
[[ -f "$CONF" ]] || die "no such app: $APP (missing apps/$APP/app.conf)"
# shellcheck source=/dev/null
source "$CONF"

PATCH_DIR="$ROOT/apps/$APP/patches"
ANCHOR_FILE="$ROOT/apps/$APP/upstream.txt"
mapfile -t PATCHES < <(find "$PATCH_DIR" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)
(( ${#PATCHES[@]} > 0 )) || die "no patches in apps/$APP/patches"

upstream_head="$(git ls-remote "$UPSTREAM_URL" "refs/heads/$UPSTREAM_BRANCH" | awk 'NR == 1 { print $1 }')"
[[ "$upstream_head" =~ ^[0-9a-f]{40}$ ]] \
    || die "could not resolve $UPSTREAM_URL ($UPSTREAM_BRANCH)"
anchor=""
[[ -f "$ANCHOR_FILE" ]] && anchor="$(tr -d '[:space:]' <"$ANCHOR_FILE")"

echo "==> $APP: upstream $upstream_head, queue anchored on ${anchor:-<unknown>}"
if [[ "$anchor" == "$upstream_head" && "$CHECK" -eq 1 ]]; then
    echo "==> The queue is already anchored on the upstream head"
    exit 0
fi

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
if ! "$ROOT/scripts/build-app.sh" "$APP" --no-build >"$log" 2>&1; then
    cat "$log" >&2
    die "the patch queue does not apply to $upstream_head; resolve the conflict by hand (see the worktree named in the log) and regenerate the queue"
fi

# A fallback means the queue does not apply to the upstream head and build-app.sh
# retried on the last commit that did work. Re-anchoring there would silently
# move the queue backwards, so treat it as the conflict it is.
if grep -qx 'PATCHWORK_RESULT_upstream_fallback=1' "$log"; then
    cat "$log" >&2
    die "the patch queue does not apply to $upstream_head (build-app.sh fell back to an older commit); resolve the conflict by hand, then re-run this script"
fi

work="$(sed -n 's/^PATCHWORK_RESULT_worktree=//p' "$log" | tail -n 1)"
built_sha="$(sed -n 's/^PATCHWORK_RESULT_upstream_commit=//p' "$log" | tail -n 1)"
patch_count="$(sed -n 's/^PATCHWORK_RESULT_patch_count=//p' "$log" | tail -n 1)"
[[ -d "$work" && -n "$built_sha" ]] || { cat "$log" >&2; die "no worktree reported by build-app.sh"; }

tmp="$(mktemp -d)"
trap 'rm -f "$log"; rm -rf "$tmp"' EXIT
git -C "$work" format-patch --no-signature --zero-commit --no-numbered \
    "$built_sha..HEAD" -o "$tmp" >/dev/null

changed=0
for patch in "$tmp"/*.patch; do
    base="$(basename "$patch")"
    if ! cmp -s "$patch" "$PATCH_DIR/$base"; then
        changed=1
    fi
done
for patch in "${PATCHES[@]}"; do
    [[ -f "$tmp/$(basename "$patch")" ]] || changed=1
done

if [[ "$CHECK" -eq 1 ]]; then
    if (( changed == 0 )) && [[ "$anchor" == "$built_sha" ]]; then
        echo "==> The queue is already anchored on $built_sha"
        exit 0
    fi
    echo "==> The queue needs a rebase onto $built_sha" >&2
    exit 1
fi

# Keep the file names the queue already had: format-patch names a file after
# the commit subject, so a re-export would rename every patch whenever a subject
# is reworded upstream of this repository, burying the real change in renames.
mapfile -t exported < <(find "$tmp" -maxdepth 1 -type f -name '*.patch' | LC_ALL=C sort)
if (( ${#exported[@]} == ${#PATCHES[@]} )); then
    staged="$(mktemp -d)"
    for i in "${!exported[@]}"; do
        cp -f "${exported[$i]}" "$staged/$(basename "${PATCHES[$i]}")"
    done
    rm -f "$PATCH_DIR"/*.patch
    cp -f "$staged"/*.patch "$PATCH_DIR/"
    rm -rf "$staged"
else
    rm -f "$PATCH_DIR"/*.patch
    cp -f "$tmp"/*.patch "$PATCH_DIR/"
fi
printf '%s\n' "$built_sha" >"$ANCHOR_FILE"
echo "==> $APP: rebased $patch_count patch(es) onto $built_sha"
if (( changed == 0 )); then
    echo "==> Patch files did not change; only the anchor was refreshed"
fi
