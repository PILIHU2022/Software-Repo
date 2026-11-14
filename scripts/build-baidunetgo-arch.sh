#!/usr/bin/env bash
#
# Package the BaiduNetGo Linux build for Arch Linux: a `.pkg.tar.zst` that
# `sudo pacman -U <file>` installs, built from the PKGBUILD mygo wrote.
#
# Usage:
#   scripts/build-baidunetgo-arch.sh --dist DIR [--image IMAGE]
#
# --dist DIR     the output directory build-baidunetgo.sh wrote. DIR/arch/ must
#                hold the PKGBUILD plus the archive and icon sources mygo
#                generated next to the linux build; build-baidunetgo.sh stages
#                them there.
# --image IMAGE  container image to run makepkg in
#                (default: archlinux:base-devel).
#
# What it does: runs the container, refreshes its package database and installs
# zstd (makepkg needs it for the .pkg.tar.zst default), creates a `builder` user
# with the host uid/gid (makepkg refuses to run as root), packages with
# `makepkg -f --noconfirm --nodeps`, then copies the resulting
# baidunetgo-<version>-1-x86_64.pkg.tar.zst next to the other installers in DIR,
# appends its `file=… size=… sha256=…` line to DIR/build-info.txt and removes
# the staging directory DIR/arch again. The package name is also printed as
# PATCHWORK_RESULT_arch_package=<name>.
#
# Requirements: docker or podman (the first one found wins) and network access
# to pull the image; no sudo when the user can talk to the container daemon.
# The container runs with --rm and without -it, so it is never interactive.
#
# This is meant for CI, where the Ubuntu runner has docker but no
# pacman/makepkg. On Arch Linux itself no container is needed: the PKGBUILD
# packages the archive next to it, so `cd DIR/arch && makepkg -si` does the same
# job (scripts/build-baidunetgo.sh leaves DIR/arch in place until this script
# removes it after a successful container run).
set -euo pipefail

IMAGE="archlinux:base-devel"
DIST=""

usage() {
    # Print the header comment only: stop at the first non-comment line.
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
}

die() {
    echo "ERROR: $*" >&2
    exit 1
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dist)
            [[ $# -ge 2 ]] || die "--dist needs a directory"
            DIST="$2"
            shift 2
            ;;
        --image)
            [[ $# -ge 2 ]] || die "--image needs an image name"
            IMAGE="$2"
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

[[ -n "$DIST" ]] || die "missing --dist DIR (try --help)"
[[ -d "$DIST" ]] || die "dist directory not found: $DIST"
DIST="$(cd "$DIST" && pwd)"
[[ "$DIST" != "/" ]] || die "refusing to use / as the dist directory"
PKGDIR="$DIST/arch"
BUILD_INFO="$DIST/build-info.txt"

[[ -f "$PKGDIR/PKGBUILD" ]] \
    || die "no PKGBUILD in $PKGDIR; run scripts/build-baidunetgo.sh first (it stages the Arch package inputs in DIR/arch)"
[[ -f "$BUILD_INFO" ]] \
    || die "no build-info.txt in $DIST; run scripts/build-baidunetgo.sh first"

shopt -s nullglob
sources=("$PKGDIR"/*.tar.gz)
shopt -u nullglob
(( ${#sources[@]} > 0 )) \
    || die "no *.tar.gz in $PKGDIR; run scripts/build-baidunetgo.sh first (the PKGBUILD packages that archive)"

if command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
elif command -v podman >/dev/null 2>&1; then
    RUNTIME="podman"
else
    die "need docker or podman to build the Arch Linux package"
fi

HOST_UID="$(id -u)"
HOST_GID="$(id -g)"
(( HOST_UID != 0 )) \
    || die "run as a normal user: makepkg refuses to run as root and this script maps the host uid/gid into the container"

LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

# Single-quoted on purpose: $HOST_UID/$HOST_GID come from the container
# environment, not from this shell. makepkg must not be root, hence the builder
# user; --nodeps skips the dependency install (and its sudo) because the package
# only needs files and fakeroot, and pacman -Sy installs zstd for compression.
# shellcheck disable=SC2016
CONTAINER_SCRIPT='
set -eu
pacman -Sy --noconfirm --needed zstd >/dev/null
useradd -m -u "$HOST_UID" -o builder >/dev/null 2>&1 || true
chown -R "$HOST_UID:$HOST_GID" /pkg
su - builder -c "cd /pkg && makepkg -f --noconfirm --nodeps"
'

echo "==> Building the Arch Linux package in $IMAGE ($RUNTIME)"
if ! "$RUNTIME" run --rm \
    --volume "$PKGDIR:/pkg" \
    --workdir /pkg \
    --env "HOST_UID=$HOST_UID" \
    --env "HOST_GID=$HOST_GID" \
    "$IMAGE" \
    bash -eu -c "$CONTAINER_SCRIPT" 2>&1 | tee "$LOG"; then
    echo "" >&2
    echo "ERROR: the $RUNTIME run failed (image pull, pacman, or makepkg); its last 60 lines:" >&2
    tail -n 60 "$LOG" >&2
    die "could not build the Arch Linux package (the output above tells whether it was makepkg or a dependency)"
fi

shopt -s nullglob
packages=("$PKGDIR"/*.pkg.tar.zst)
shopt -u nullglob
(( ${#packages[@]} > 0 )) \
    || die "makepkg finished but produced no *.pkg.tar.zst in $PKGDIR"

for package in "${packages[@]}"; do
    name="$(basename "$package")"
    cp -f "$package" "$DIST/"
    printf 'file=%s size=%s sha256=%s\n' \
        "$name" \
        "$(stat -c %s "$package" 2>/dev/null || stat -f %z "$package")" \
        "$(sha256sum "$package" | awk '{print $1}')" >>"$BUILD_INFO"
    echo "==> Arch package: $DIST/$name"
    echo "PATCHWORK_RESULT_arch_package=$name"
done

rm -rf "$PKGDIR"
