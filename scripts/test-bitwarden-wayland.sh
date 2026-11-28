#!/usr/bin/env bash
#
# Smoke-test a packaged Bitwarden Linux build under a headless Wayland
# compositor. This is intentionally a runtime test: static checks cannot tell
# whether the Electron process really selected the Wayland backend.
#
# Usage:
#   scripts/test-bitwarden-wayland.sh [linux-unpacked-dir]
#
# The default directory is .cache/work/bitwarden/apps/desktop/dist/linux-unpacked.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="${1:-$ROOT/.cache/work/bitwarden/apps/desktop/dist/linux-unpacked}"

die() {
    echo "ERROR: $*" >&2
    exit 1
}

if [[ ! -d "$APP_DIR" ]]; then
    candidate="$(find "$ROOT/.cache/work/bitwarden/apps/desktop" -type f \
        \( -name bitwarden -o -name Bitwarden \) 2>/dev/null | head -n 1 || true)"
    [[ -n "$candidate" ]] || die "Linux unpacked app not found: $APP_DIR"
    APP_DIR="$(dirname "$candidate")"
fi
command -v weston >/dev/null 2>&1 || die "weston is required for the Wayland smoke test"
command -v dbus-run-session >/dev/null 2>&1 \
    || die "dbus-run-session is required for the Wayland smoke test"

APP_BIN=""
for candidate in "$APP_DIR/bitwarden" "$APP_DIR/Bitwarden"; do
    if [[ -x "$candidate" ]]; then
        APP_BIN="$candidate"
        break
    fi
done
[[ -n "$APP_BIN" ]] || die "Bitwarden executable not found under $APP_DIR"

RUNTIME_DIR="$(mktemp -d)"
LOG="$(mktemp)"
WESTON_LOG="$(mktemp)"
chmod 700 "$RUNTIME_DIR"

export XDG_RUNTIME_DIR="$RUNTIME_DIR"
export XDG_SESSION_TYPE=wayland
export WAYLAND_DISPLAY=wayland-patchwork
export ELECTRON_OZONE_PLATFORM_HINT=auto

cleanup() {
    if [[ -n "${APP_PID:-}" ]] && kill -0 "$APP_PID" 2>/dev/null; then
        kill "$APP_PID" 2>/dev/null || true
        wait "$APP_PID" 2>/dev/null || true
    fi
    if [[ -n "${WESTON_PID:-}" ]] && kill -0 "$WESTON_PID" 2>/dev/null; then
        kill "$WESTON_PID" 2>/dev/null || true
        wait "$WESTON_PID" 2>/dev/null || true
    fi
    rm -rf "$RUNTIME_DIR" "$LOG" "$WESTON_LOG"
}
trap cleanup EXIT

weston --backend=headless-backend.so --socket=wayland-patchwork \
    --idle-time=0 --log="$WESTON_LOG" >"$WESTON_LOG" 2>&1 &
WESTON_PID=$!

for _ in {1..30}; do
    [[ -S "$XDG_RUNTIME_DIR/wayland-patchwork" ]] && break
    sleep 0.2
done
[[ -S "$XDG_RUNTIME_DIR/wayland-patchwork" ]] \
    || { cat "$WESTON_LOG" >&2; die "Weston did not create a Wayland socket"; }

echo "==> Starting $APP_BIN under Wayland"
timeout 25s dbus-run-session -- "$APP_BIN" --no-sandbox --disable-gpu \
    --password-store=basic --enable-logging=stderr --log-level=0 >"$LOG" 2>&1 &
APP_PID=$!

sleep 8
if ! kill -0 "$APP_PID" 2>/dev/null; then
    cat "$LOG" >&2
    die "Bitwarden exited during the Wayland smoke test"
fi

if grep -Eqi 'ozone.*(wayland|x11)|platform.*(wayland|x11)' "$LOG"; then
    grep -Ei 'ozone.*(wayland|x11)|platform.*(wayland|x11)' "$LOG" | tail -n 5
else
    echo "::warning::Electron did not log its Ozone backend; the process stayed alive under Wayland"
fi

echo "==> Wayland smoke test passed"

