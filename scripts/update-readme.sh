#!/usr/bin/env bash
#
# Refresh the "Apps" table in README.md / README.zh-CN.md after a build.
#
# Usage:
#   scripts/update-readme.sh [--apk-dir DIR] [--state-json JSON] [--check]
#                            [--readme FILE]...
#
# For every apps/<app>/app.conf the script looks up the newest built APK in
# --apk-dir (matched by PACKAGE), reads its versionCode/versionName with aapt,
# takes the upstream revision from --state-json or fdroid/.build-state.json
# (falling back to the APK's neighbouring build-info.txt), and rewrites the
# row's "Upstream revision", "Patches" and "Built version" cells in place.
#
# Apps without a freshly built APK keep the values already in the README, so a
# partial build (one matrix leg failed) never rolls another app's row back.
#
# Options:
#   --apk-dir DIR     directory with the built APKs (default: dist)
#   --state-json JSON upstream SHA map, e.g. '{"expenses":"abc..."}'
#                     (default: fdroid/.build-state.json)
#   --readme FILE     README to update; may be repeated
#                     (default: README.md and README.zh-CN.md when present)
#   --check           report whether the READMEs are in sync; exit 1 if not,
#                     without writing anything
#   -h, --help        show this help
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$ROOT/fdroid/.build-state.json"

usage() {
    sed -n '3,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

APK_DIR="$ROOT/dist"
STATE_JSON=""
CHECK=0
READMES=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk-dir)
            [[ $# -ge 2 ]] || die "--apk-dir needs a directory"
            APK_DIR="$2"
            shift 2
            ;;
        --state-json)
            [[ $# -ge 2 ]] || die "--state-json needs a JSON object"
            STATE_JSON="$2"
            shift 2
            ;;
        --readme)
            [[ $# -ge 2 ]] || die "--readme needs a file"
            READMES+=("$2")
            shift 2
            ;;
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

[[ -d "$APK_DIR" ]] || die "APK directory not found: $APK_DIR"
APK_DIR="$(cd "$APK_DIR" && pwd)"

if [[ ${#READMES[@]} -eq 0 ]]; then
    for candidate in "$ROOT/README.md" "$ROOT/README.zh-CN.md"; do
        [[ -f "$candidate" ]] && READMES+=("$candidate")
    done
fi
[[ ${#READMES[@]} -gt 0 ]] || die "no README found"
for readme in "${READMES[@]}"; do
    [[ -f "$readme" ]] || die "README not found: $readme"
done

if [[ -n "$STATE_JSON" ]]; then
    printf '%s' "$STATE_JSON" | jq -e 'type == "object"' >/dev/null 2>&1 \
        || die "--state-json is not a JSON object"
fi

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

# package -> newest built APK (by versionCode)
declare -A APK_PATH=() APK_CODE=() APK_NAME=()
while IFS= read -r apk; do
    line="$("$AAPT" dump badging "$apk" 2>/dev/null \
        | sed -nE "s/^package: name='([^']*)' versionCode='([0-9]+)' versionName='([^']*)'.*/\1\t\2\t\3/p" \
        | head -n 1)"
    [[ -n "$line" ]] || continue
    IFS=$'\t' read -r pkg code name <<<"$line"
    if [[ -z "${APK_CODE[$pkg]:-}" ]] || ((10#$code > 10#${APK_CODE[$pkg]})); then
        APK_PATH[$pkg]="$apk"
        APK_CODE[$pkg]="$code"
        APK_NAME[$pkg]="$name"
    fi
done < <(find "$APK_DIR" -type f -name '*.apk' | LC_ALL=C sort)

state_sha() {
    local app="$1"
    if [[ -n "$STATE_JSON" ]]; then
        printf '%s' "$STATE_JSON" | jq -r --arg app "$app" '.[$app] // empty'
    elif [[ -f "$STATE_FILE" ]]; then
        jq -r --arg app "$app" '.[$app] // empty' "$STATE_FILE"
    fi
}

build_info_field() {
    local apk="$1" key="$2" info
    info="$(dirname "$apk")/build-info.txt"
    [[ -f "$info" ]] || return 0
    awk -F '=' -v key="$key" '$1 == key { print $2; exit }' "$info"
}

# Rewrite one row: the first line in the "Apps" table that links to $url.
# Cells are rebuilt from the existing line, so the label, branch annotation and
# any prose around the table are preserved.
rewrite_row() {
    local file="$1" url="$2" short="$3" patches="$4" version="$5" code="$6" abi="$7"
    awk -v url="]($url)" -v short="$short" -v patches="$patches" \
        -v version="$version" -v code="$code" -v abi="$abi" '
        BEGIN { FS = "|" }
        {
            line = $0
            if (!done && line ~ /^\|/ && index(line, url) > 0) {
                n = split(line, c, "|")
                # c[1]="" c[2]=app c[3]=upstream c[4]=revision c[5]=patches c[6]=built
                if (short != "") {
                    old = c[4]
                    gsub(/[` ]/, "", old)
                    len = length(old)
                    if (len < 7 || len > 12) len = 7
                    c[4] = " `" substr(short, 1, len) "` "
                }
                c[5] = " " patches " "
                popen = (index(c[6], "\357\274\210") > 0) ? "\357\274\210" : "("
                pclose = (popen == "(") ? ")" : "\357\274\211"
                sp = (popen == "(") ? " " : ""
                sep = (popen == "(") ? ", " : "\357\274\214"
                c[6] = " `" version "`" sp popen code ((abi != "") ? sep abi : "") pclose " "
                out = c[1]
                for (i = 2; i <= n; i++) out = out "|" c[i]
                print out
                done = 1
                next
            }
            print
        }
    ' "$file"
}

fail=0
updated=0
for conf in "$ROOT"/apps/*/app.conf; do
    [[ -f "$conf" ]] || continue
    # shellcheck source=/dev/null
    source "$conf"

    if [[ -z "${APK_CODE[$PACKAGE]:-}" ]]; then
        echo "    $APP_ID: no built APK for $PACKAGE, keeping README values"
        continue
    fi

    apk="${APK_PATH[$PACKAGE]}"
    code="${APK_CODE[$PACKAGE]}"
    version="${APK_NAME[$PACKAGE]}"
    abi="${BUILD_ABI:-}"
    patches="$(find "$ROOT/apps/$APP_ID/patches" -maxdepth 1 -type f -name '*.patch' | wc -l)"

    sha="$(state_sha "$APP_ID" || true)"
    [[ -n "$sha" ]] || sha="$(build_info_field "$apk" upstream_commit || true)"

    url="${UPSTREAM_URL%.git}"
    short=""
    if [[ "$sha" =~ ^[0-9a-f]{7,40}$ ]]; then
        short="$sha"
    fi

    for readme in "${READMES[@]}"; do
        tmp="$(mktemp)"
        rewrite_row "$readme" "$url" "$short" "$patches" "$version" "$code" "$abi" >"$tmp"
        [[ -s "$tmp" ]] || { rm -f "$tmp"; die "failed to rewrite $readme"; }
        if cmp -s "$readme" "$tmp"; then
            rm -f "$tmp"
            continue
        fi
        if [[ "$CHECK" -eq 1 ]]; then
            echo "OUT-OF-SYNC $readme ($APP_ID)" >&2
            diff -u "$readme" "$tmp" >&2 || true
            rm -f "$tmp"
            fail=1
            continue
        fi
        chmod --reference="$readme" "$tmp"
        mv "$tmp" "$readme"
        updated=$((updated + 1))
        echo "    $readme: $APP_ID -> ${short:0:8}, $patches patch(es), $version ($code${abi:+, $abi})"
    done
done

if [[ "$CHECK" -eq 1 ]]; then
    if [[ "$fail" -ne 0 ]]; then
        echo "README tables are out of sync; run scripts/update-readme.sh" >&2
        exit 1
    fi
    echo "README tables are in sync."
    exit 0
fi

echo "==> README updated ($updated file(s))"
