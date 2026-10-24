#!/usr/bin/env bash
#
# Report which apps should be built.
#
# Usage:
#   scripts/check-upstream.sh [--all] [--apps "expenses screentime"]
#
# --apps forces the named apps (space/comma separated) regardless of the
# upstream state; it is used by CI when a push only touches some apps.
#
# Prints exactly one line of JSON on stdout:
#   {"changed":true,"matrix":{"include":[{"app":"expenses","sha":"..."}]}}
# and a human-readable summary on stderr.
#
# The JSON is safe to pass to GitHub Actions fromJSON().
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$ROOT/fdroid/.build-state.json"

usage() {
    cat <<'EOF'
Usage: scripts/check-upstream.sh [--all] [--apps "expenses screentime"]

Queries each app's upstream branch and compares it with
fdroid/.build-state.json.

Options:
  --all            treat every app as changed (used for manual/forced builds)
  --apps LIST      only build these apps (space or comma separated)
                   and always treat them as changed
  -h, --help       show this help

stdout: one line of JSON: {"changed":bool,"matrix":{"include":[...]}}
stderr: human-readable per-app summary
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

FORCE_ALL=0
TARGETS=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)
            FORCE_ALL=1
            shift
            ;;
        --apps)
            [[ $# -ge 2 ]] || die "--apps needs a value (e.g. --apps \"expenses screentime\")"
            TARGETS="${2//,/ }"
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

target_selected() {
    local wanted="$1" name
    for name in $TARGETS; do
        [[ "$name" == "$wanted" ]] && return 0
    done
    return 1
}

command -v jq >/dev/null 2>&1 || die "jq is required"

export GIT_TERMINAL_PROMPT=0

TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT
: >"$TMP"

checked=0
changed_count=0

for conf in "$ROOT"/apps/*/app.conf; do
    [[ -f "$conf" ]] || continue
    # shellcheck source=/dev/null
    source "$conf"

    if [[ -n "$TARGETS" ]] && ! target_selected "$APP_ID"; then
        continue
    fi

    checked=$((checked + 1))

    if ! sha="$(git ls-remote "$UPSTREAM_URL" "refs/heads/$UPSTREAM_BRANCH" | awk 'NR == 1 { print $1 }')"; then
        die "git ls-remote failed for $UPSTREAM_URL ($UPSTREAM_BRANCH)"
    fi
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] \
        || die "could not resolve $UPSTREAM_URL ($UPSTREAM_BRANCH) for $APP_ID"

    previous=""
    if [[ -f "$STATE_FILE" ]]; then
        previous="$(jq -r --arg app "$APP_ID" '.[$app] // empty' "$STATE_FILE")" \
            || die "invalid JSON in $STATE_FILE"
    fi

    forced=0
    [[ "$FORCE_ALL" -eq 1 ]] && forced=1
    [[ -n "$TARGETS" ]] && forced=1

    if [[ "$forced" -eq 1 || "$sha" != "$previous" ]]; then
        changed_count=$((changed_count + 1))
        jq -cn --arg app "$APP_ID" --arg sha "$sha" '{app: $app, sha: $sha}' >>"$TMP"
        if [[ -n "$TARGETS" ]]; then
            printf '%-10s CHANGED  %s (selected)\n' "$APP_ID" "$sha" >&2
        elif [[ "$FORCE_ALL" -eq 1 ]]; then
            printf '%-10s CHANGED  %s (forced)\n' "$APP_ID" "$sha" >&2
        elif [[ -z "$previous" ]]; then
            printf '%-10s CHANGED  %s (no previous build)\n' "$APP_ID" "$sha" >&2
        else
            printf '%-10s CHANGED  %s (was %s)\n' "$APP_ID" "$sha" "$previous" >&2
        fi
    else
        printf '%-10s unchanged %s\n' "$APP_ID" "$sha" >&2
    fi
done

[[ "$checked" -gt 0 ]] || {
    if [[ -n "$TARGETS" ]]; then
        die "no app matched --apps \"$TARGETS\""
    fi
    die "no app configs found under $ROOT/apps"
}

if [[ -s "$TMP" ]]; then
    include="$(jq -s -c . "$TMP")"
    changed="true"
else
    include='[]'
    changed="false"
fi

printf '%s\n' "$(jq -cn --argjson changed "$changed" --argjson include "$include" \
    '{changed: $changed, matrix: {include: $include}}')"

printf '%d app(s) checked, %d changed\n' "$checked" "$changed_count" >&2
