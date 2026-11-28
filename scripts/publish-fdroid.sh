#!/usr/bin/env bash
#
# Publish built APKs into the local F-Droid repository.
#
# Usage:
#   scripts/publish-fdroid.sh --apk-dir DIR [--state-json '{"expenses":"sha",...}']
#
# The script copies APKs into fdroid/repo/, keeps at most three APKs per
# package (newest versionCode wins), installs the repository signing key as
# fdroid/keystore.jks (gitignored), and runs `fdroid update`.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_DIR="$ROOT/fdroid/repo"
STATE_FILE="$ROOT/fdroid/.build-state.json"
ICON_SRC_DIR="$ROOT/fdroid/icons-src"

usage() {
    cat <<'EOF'
Usage: scripts/publish-fdroid.sh --apk-dir DIR [--state-json JSON]

Options:
  --apk-dir DIR      directory containing built APKs (searched recursively)
  --state-json JSON  upstream commit map, e.g. '{"expenses":"abc123..."}'
  -h, --help         show this help

Environment:
  FDROID_BIN         fdroid executable (default: fdroid from PATH)
  ANDROID_HOME       Android SDK (default: <repo>/.toolchain/android-sdk if present)

Incoming APKs are discovered recursively. For each (package, versionCode)
pair only one APK is published: an incoming build replaces any older file with
the same pair, and duplicate incoming files are collapsed to the last one in
sorted order. At most three APKs per package (newest versionCode first) are
kept in fdroid/repo/. The repository is indexed with
`fdroid update --verbose`; fdroid/repo/index-v2.json must exist afterwards or
the script exits non-zero.
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

APK_DIR=""
STATE_JSON=""
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
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown argument: $1 (try --help)"
            ;;
    esac
done

[[ -n "$APK_DIR" ]] || {
    usage >&2
    exit 2
}
[[ -d "$APK_DIR" ]] || die "APK directory not found: $APK_DIR"
APK_DIR="$(cd "$APK_DIR" && pwd)"
mkdir -p "$REPO_DIR"

if [[ -n "$STATE_JSON" ]]; then
    printf '%s' "$STATE_JSON" | jq -e 'type == "object"' >/dev/null 2>&1 \
        || die "--state-json is not a JSON object"
fi

if [[ -z "${ANDROID_HOME:-}" && -d "$ROOT/.toolchain/android-sdk" ]]; then
    export ANDROID_HOME="$ROOT/.toolchain/android-sdk"
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

apk_package_and_code() {
    local apk="$1" line
    line="$("$AAPT" dump badging "$apk" 2>/dev/null \
        | sed -nE "s/^package: name='([^']*)' versionCode='([0-9]+)'.*/\1 \2/p" \
        | head -n 1)"
    [[ -n "$line" ]] || die "could not read package/versionCode from $apk"
    printf '%s\n' "$line"
}

# Search recursively: the CI workflow downloads every matrix artifact into its
# own `dist/patchwork-<app>/` directory, so APKs are usually nested one level
# deep. Keeping the artifacts in separate directories also avoids two apps
# overwriting each other's build-info.txt.
mapfile -t FOUND < <(find "$APK_DIR" -type f -name '*.apk' | LC_ALL=C sort)
[[ ${#FOUND[@]} -gt 0 ]] || die "no APKs found under $APK_DIR"

# Collapse duplicate (package, versionCode) inputs. A retried matrix job, a
# local APK passed next to the CI artifact, or a re-run for the same upstream
# version can hand us several binaries for one package/version pair. Only one
# may be published: the last file in sorted order wins, deterministically.
declare -A INCOMING=()
INCOMING_ORDER=()
for apk in "${FOUND[@]}"; do
    read -r pkg code <<<"$(apk_package_and_code "$apk")"
    key="$pkg $code"
    if [[ -n "${INCOMING[$key]:-}" ]]; then
        echo "    duplicate $pkg ($code): $(basename "${INCOMING[$key]}") superseded by $(basename "$apk")"
    else
        INCOMING_ORDER+=("$key")
    fi
    INCOMING[$key]="$apk"
done

echo "==> Publishing ${#INCOMING_ORDER[@]} unique APK(s) into fdroid/repo"
for key in "${INCOMING_ORDER[@]}"; do
    apk="${INCOMING[$key]}"
    read -r pkg code <<<"$key"
    # Drop any previously published build that has the same package and
    # versionCode. The incoming APK is the authoritative binary for that
    # pair; leaving the old file behind would let the index reference a stale
    # binary (same versionCode, different bytes).
    while IFS= read -r stale; do
        [[ "$stale" -ef "$apk" ]] && continue
        read -r stale_pkg stale_code <<<"$(apk_package_and_code "$stale")"
        if [[ "$stale_pkg" == "$pkg" && "$stale_code" == "$code" ]]; then
            rm -f -- "$stale"
            echo "    replaced $(basename "$stale") ($pkg $code)"
        fi
    done < <(find "$REPO_DIR" -maxdepth 1 -type f -name '*.apk')
    if [[ "$apk" -ef "$REPO_DIR/$(basename "$apk")" ]]; then
        echo "    $(basename "$apk") ($pkg $code) already in fdroid/repo"
    else
        cp -f "$apk" "$REPO_DIR/$(basename "$apk")"
        echo "    $(basename "$apk") ($pkg $code)"
    fi
done

# Keep only the three newest APKs per package. fdroid update would otherwise
# accumulate every historical build forever.
echo "==> Pruning old APKs (max 3 per package)"
TMP="$(mktemp)"
GITKEEP_BACKUP=""
trap 'if [[ -n "${GITKEEP_BACKUP:-}" && -f "${GITKEEP_BACKUP}" ]]; then mv -f "${GITKEEP_BACKUP}" "$REPO_DIR/.gitkeep"; fi; rm -f "$TMP"' EXIT
: >"$TMP"
for apk in "$REPO_DIR"/*.apk; do
    [[ -f "$apk" ]] || continue
    read -r pkg code <<<"$(apk_package_and_code "$apk")"
    printf '%s\t%s\t%s\n' "$pkg" "$code" "$apk" >>"$TMP"
done

mapfile -t PACKAGES < <(cut -f1 "$TMP" | LC_ALL=C sort -u)
for pkg in "${PACKAGES[@]}"; do
    while IFS= read -r stale; do
        [[ -n "$stale" ]] || continue
        rm -f -- "$stale"
        echo "    removed $(basename "$stale") ($pkg)"
    done < <(
        awk -F '\t' -v pkg="$pkg" '$1 == pkg { print $2 "\t" $3 }' "$TMP" \
            | sort -t $'\t' -k1,1nr -k2,2 \
            | tail -n +4 \
            | cut -f2-
    )
done

echo "==> Installing repository signing key"
[[ -f "$ROOT/signing/patchwork-test.jks" ]] || die "missing signing/patchwork-test.jks"
install -m 600 "$ROOT/signing/patchwork-test.jks" "$ROOT/fdroid/keystore.jks"

FDROID_BIN="${FDROID_BIN:-fdroid}"
if [[ "$FDROID_BIN" == */* ]]; then
    [[ -x "$FDROID_BIN" ]] || die "FDROID_BIN is not executable: $FDROID_BIN"
else
    command -v "$FDROID_BIN" >/dev/null 2>&1 || die "fdroid not found (set FDROID_BIN)"
fi

# fdroid update cannot read the adaptive XML icons these APKs use, so it never
# writes an icon for them.  Put the 512x512 source icon in both places it looks:
# repo/<package>/<locale>/icon.png (index-v2) and repo/icons/<package>.<code>.png
# (legacy index-v1, patched below).  See fdroid/icons-src/ for the sources.
echo "==> Installing app icons"
declare -A BEST_CODE=()
while IFS=$'\t' read -r pkg code _; do
    [[ -n "$pkg" ]] || continue
    if [[ -z "${BEST_CODE[$pkg]:-}" || "$code" -gt "${BEST_CODE[$pkg]}" ]]; then
        BEST_CODE[$pkg]="$code"
    fi
done <"$TMP"
for pkg in "${PACKAGES[@]}"; do
    code="${BEST_CODE[$pkg]:-}"
    src="$ICON_SRC_DIR/$pkg.png"
    [[ -n "$code" ]] || continue
    if [[ ! -f "$src" ]]; then
        echo "::warning::No icon source for $pkg (expected fdroid/icons-src/$pkg.png)"
        continue
    fi
    mkdir -p "$REPO_DIR/$pkg/en-US" "$REPO_DIR/icons"
    cp -f "$src" "$REPO_DIR/$pkg/en-US/icon.png"
    cp -f "$src" "$REPO_DIR/icons/$pkg.$code.png"
    echo "    $pkg ($code)"
done

echo "==> Running fdroid update --verbose"
# fdroid update treats every non-index file in repo/ as a repository file and
# rejects zero-size ones, so hide the .gitkeep placeholder while it runs.
if [[ -f "$REPO_DIR/.gitkeep" ]]; then
    GITKEEP_BACKUP="$(mktemp)"
    mv "$REPO_DIR/.gitkeep" "$GITKEEP_BACKUP"
fi
(
    cd "$ROOT/fdroid"
    "$FDROID_BIN" update --verbose </dev/null
)
if [[ -n "$GITKEEP_BACKUP" ]]; then
    mv -f "$GITKEEP_BACKUP" "$REPO_DIR/.gitkeep"
    GITKEEP_BACKUP=""
fi

# `fdroid update` only fills the icon field from APK raster icons, and adding a
# new package can prune the content-addressed index-v2 icons of the others.
# fdroid-icons.py restores those, patches the legacy index-v1 field and
# index.xml, and generates the icons-<density>/ copies; signing is left to
# `fdroid signindex` below so the jars are not signed twice.
#
# The per-density resize needs Pillow. The runner's system python3 often lacks
# it, but the fdroidserver venv that runs `fdroid update` always has it, so
# prefer the interpreter next to the fdroid executable when there is one.
ICONS_PYTHON="python3"
if [[ -n "${FDROID_BIN:-}" ]]; then
    fdroid_real="$(readlink -f "$(command -v "$FDROID_BIN" 2>/dev/null || printf '%s' "$FDROID_BIN")" 2>/dev/null || true)"
    if [[ -n "$fdroid_real" && -x "$(dirname "$fdroid_real")/python" ]]; then
        ICONS_PYTHON="$(dirname "$fdroid_real")/python"
    fi
fi
echo "==> Repairing index icons"
"$ICONS_PYTHON" "$ROOT/scripts/fdroid-icons.py" --repo "$REPO_DIR" --no-sign
echo "==> Re-signing index-v1 and entry (and index.jar)"
(
    cd "$ROOT/fdroid"
    "$FDROID_BIN" signindex </dev/null
)

if [[ -n "$STATE_JSON" ]]; then
    base='{}'
    if [[ -f "$STATE_FILE" ]]; then
        base="$(cat "$STATE_FILE")"
    fi
    printf '%s' "$base" | jq -S --argjson new "$STATE_JSON" '. * $new' >"$STATE_FILE.tmp"
    mv "$STATE_FILE.tmp" "$STATE_FILE"
    echo "==> Updated $STATE_FILE"
fi

echo "==> Index files"
while IFS= read -r index; do
    [[ -n "$index" ]] || continue
    printf '    %-24s %10s bytes\n' "$(basename "$index")" "$(stat -c %s "$index")"
done < <(find "$REPO_DIR" -maxdepth 1 -type f \( -name 'index-*' -o -name 'entry.jar' \) | LC_ALL=C sort)

[[ -f "$REPO_DIR/index-v2.json" ]] \
    || die "fdroid update did not produce fdroid/repo/index-v2.json"
python3 "$ROOT/scripts/fdroid-icons.py" --repo "$REPO_DIR" --check \
    || die "F-Droid indexes have missing or inconsistent icons"

# Final guard: never leave a broken index behind. A concurrent publish or a
# botched merge must not be able to stage git conflict markers or invalid
# JSON/XML into fdroid/repo, because the Pages step deploys this directory
# verbatim. Assert the three indexes parse and carry no conflict markers.
if ! python3 - "$REPO_DIR" <<'PY'
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

repo = Path(sys.argv[1])
errors = []

for name in ("index-v1.json", "index-v2.json"):
    try:
        json.loads((repo / name).read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{name}: invalid JSON ({exc})")

try:
    ET.parse(repo / "index.xml")
except Exception as exc:
    errors.append(f"index.xml: invalid XML ({exc})")

marker = re.compile(r"^(<<<<<<<|=======|>>>>>>>)")
for name in ("index-v1.json", "index-v2.json", "index.xml"):
    path = repo / name
    if not path.is_file():
        errors.append(f"{name}: missing")
        continue
    for lineno, line in enumerate(
        path.read_text(encoding="utf-8", errors="replace").splitlines(), 1
    ):
        if marker.match(line):
            errors.append(f"{name}: git conflict marker on line {lineno}")
            break

if errors:
    for message in errors:
        print(f"  {message}", file=sys.stderr)
    sys.exit(1)

print("==> Verified indexes: index-v1/v2 JSON parse, index.xml parses, no conflict markers")
PY
then
    die "F-Droid indexes are corrupt (invalid JSON/XML or git conflict markers)"
fi

echo "==> Published repository index: $REPO_DIR/index-v2.json"
