#!/usr/bin/env python3
"""Write the BaiduNetGo update feed, usually desktop/baidunetgo/update.json.

Usage:
    scripts/baidunetgo-feed.py --dist DIR --tag TAG --version VERSION \
        --repo OWNER/NAME --out FILE [--notes FILE] [--platforms LIST] \
        [--base URL]

The workflow runs this once per successful build, after it uploads that
build's installers to a GitHub release: the feed names the newest version,
says what changed, and points at the release assets for every platform. The
app's upstream internal/updates package reads this document to offer a manual
update, and the raw URL of the committed file is the default feed linked into
the binaries at build time.

--dist is the build output directory (build-info.txt and subdirectories are
ignored), --tag is the release the assets are attached to, --notes is a
markdown file with the changelog (without it the notes are synthesized from
build-info.txt), and --platforms defaults to the two x86 targets the build
publishes: linux/amd64 and windows/amd64 (linux-amd64 also works).

A platform named in --platforms with no usable installer in --dist is an
error, never a feed entry: a feed that silently drops a platform would leave
those users without an update.

Standard library only. Exit status: 0 wrote the feed, 1 error (message on
stderr, prefixed "baidunetgo-feed: ").
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
import urllib.parse
from typing import NoReturn

# Where the feed lives in this repository, on the branch it is committed to.
# Its raw URL is what the built binaries check by default.
FEED_FILE = "desktop/baidunetgo/update.json"

DEFAULT_PLATFORMS = "linux/amd64,windows/amd64"

# The installer each platform prefers, matched on the end of the file name:
# the names the builders write, only x86 is published.
ASSET_SUFFIXES = {
    "linux-amd64": ("-linux-amd64.tar.gz",),
    "windows-amd64": ("-windows-amd64.zip",),
}


def die(message: str) -> NoReturn:
    """Report one error the way the workflow expects and stop."""
    print(f"baidunetgo-feed: {message}", file=sys.stderr)
    raise SystemExit(1)


class FeedParser(argparse.ArgumentParser):
    """Report bad arguments like any other error, not argparse's own way."""

    def error(self, message: str) -> NoReturn:
        self.print_usage(sys.stderr)
        die(message)


def normalize_platform(token: str) -> str:
    """Return GOOS-GOARCH for a platform given as GOOS/GOARCH or GOOS-GOARCH.

    The feed itself keys addresses GOOS-GOARCH (as the app looks them up),
    while the build directories use the same spelling with a dash.
    """
    return token.strip().replace("/", "-")


def is_fallback_asset(platform: str, name: str) -> bool:
    """Whether name can stand in for the platform's preferred installer."""
    if platform == "linux-amd64":
        # The .deb mygo builds alongside the tarball; either installs the app.
        return name.endswith(".deb") and ("_amd64" in name or "-amd64" in name)
    if platform == "windows-amd64":
        # The NSIS setup exe, used when no portable zip was built.
        return name.lower().endswith(".exe") and "-amd64" in name
    return False


def pick_asset(platform: str, names: list[str]) -> str | None:
    """Pick one installer for platform among the file names in --dist."""
    for suffix in ASSET_SUFFIXES[platform]:
        matches = sorted(name for name in names if name.endswith(suffix))
        if matches:
            return matches[0]
    matches = sorted(name for name in names if is_fallback_asset(platform, name))
    return matches[0] if matches else None


def read_build_info(path: str) -> dict[str, str]:
    """Read build-info.txt as key=value fields; a missing file is not fatal."""
    fields: dict[str, str] = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                key, separator, value = line.partition("=")
                if separator:
                    fields[key.strip()] = value.strip()
    except FileNotFoundError:
        pass
    except OSError as exc:
        print(f"baidunetgo-feed: warning: cannot read {path}: {exc}", file=sys.stderr)
    return fields


def synthesized_notes(build_info_path: str) -> str:
    """Compose the notes from build-info.txt when no --notes file was given.

    Every field is optional: a hand-made dist may have no build-info.txt and
    an older build may not record one of the fields, so missing pieces drop
    out of the sentence instead of failing the feed.
    """
    fields = read_build_info(build_info_path)
    parts = ["社区修复版（x86）"]
    commit = fields.get("upstream_commit", "")
    if commit:
        parts.append(f"基于上游 {commit[:7]}")
    patch_count = fields.get("patch_count", "")
    if patch_count:
        parts.append(f"含 {patch_count} 个补丁")
    return "，".join(parts) + "。"


def read_notes_file(path: str) -> str:
    """Return the changelog file's text, or die if it cannot be read."""
    try:
        with open(path, encoding="utf-8") as handle:
            return handle.read().strip()
    except OSError as exc:
        die(f"cannot read --notes {path}: {exc}")


def discard(path: str) -> None:
    """Remove a partial temporary file, ignoring one that is already gone."""
    try:
        os.unlink(path)
    except OSError:
        pass


def write_feed(path: str, feed: dict[str, object]) -> None:
    """Write the feed atomically, world-readable, with a trailing newline.

    A half-written feed would break every installed app's update check, so
    the bytes go to a temporary file next to the target and are renamed over
    it in one step.
    """
    directory = os.path.dirname(os.path.abspath(path))
    if not os.path.isdir(directory):
        die(f"output directory does not exist: {directory}")
    text = json.dumps(feed, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    try:
        handle = tempfile.NamedTemporaryFile(
            mode="w",
            encoding="utf-8",
            prefix=".update.json.",
            suffix=".tmp",
            dir=directory,
            delete=False,
        )
    except OSError as exc:
        die(f"cannot write {path}: {exc}")
    tmp_path = handle.name
    try:
        with handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        # mkstemp creates 0600; the committed feed must be readable for all.
        os.chmod(tmp_path, 0o644)
        os.replace(tmp_path, path)
    except OSError as exc:
        discard(tmp_path)
        die(f"cannot write {path}: {exc}")
    except BaseException:
        discard(tmp_path)
        raise


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = FeedParser(
        prog="baidunetgo-feed.py",
        description="Write the BaiduNetGo update feed for one build.",
        allow_abbrev=False,
    )
    parser.add_argument("--dist", required=True, help="build output directory")
    parser.add_argument("--tag", required=True, help="release tag holding the assets")
    parser.add_argument("--version", required=True, help="version the feed announces")
    parser.add_argument("--repo", required=True, help="OWNER/NAME publishing the release")
    parser.add_argument("--out", required=True, help="feed file to write")
    parser.add_argument(
        "--notes",
        help="markdown file with the changelog (default: built from build-info.txt)",
    )
    parser.add_argument(
        "--platforms",
        default=DEFAULT_PLATFORMS,
        help=f"comma-separated platforms to publish (default: {DEFAULT_PLATFORMS})",
    )
    parser.add_argument(
        "--base",
        help="asset download prefix (default: https://github.com/<repo>/releases/download)",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    dist = os.path.abspath(args.dist)
    if not os.path.isdir(dist):
        die(f"--dist is not a directory: {args.dist}")
    repo = args.repo.strip()
    if repo.count("/") != 1 or not all(repo.split("/")):
        die(f"--repo must be OWNER/NAME, got {args.repo!r}")
    tag = args.tag.strip()
    if not tag:
        die("--tag is empty")
    version = args.version.strip()
    if not version:
        die("--version is empty")

    # Accept GOOS/GOARCH and GOOS-GOARCH, deduplicated and in a stable order.
    platforms: list[str] = []
    for token in args.platforms.split(","):
        if not token.strip():
            continue
        platform = normalize_platform(token)
        if platform not in ASSET_SUFFIXES:
            published = ", ".join(sorted(ASSET_SUFFIXES))
            die(f"unsupported platform {token.strip()!r}; published platforms: {published}")
        if platform not in platforms:
            platforms.append(platform)
    if not platforms:
        die("--platforms is empty")

    # Only regular files directly in --dist are assets: build-info.txt is
    # build metadata and the portable zip already contains its own tree.
    names = sorted(
        name
        for name in os.listdir(dist)
        if os.path.isfile(os.path.join(dist, name)) and name != "build-info.txt"
    )

    base = (args.base or f"https://github.com/{repo}/releases/download").rstrip("/")
    urls: dict[str, str] = {}
    missing: list[str] = []
    for platform in platforms:
        name = pick_asset(platform, names)
        if name is None:
            missing.append(platform)
            continue
        quoted_tag = urllib.parse.quote(tag, safe="")
        quoted_name = urllib.parse.quote(name, safe="")
        urls[platform] = f"{base}/{quoted_tag}/{quoted_name}"
    if missing:
        die(
            f"no installer in {dist} for {', '.join(missing)}: "
            "a feed without a platform would leave those users without an update"
        )

    notes = read_notes_file(args.notes) if args.notes else ""
    if not notes:
        notes = synthesized_notes(os.path.join(dist, "build-info.txt"))

    write_feed(os.path.abspath(args.out), {"version": version, "notes": notes, "url": urls})

    feed_url = f"https://raw.githubusercontent.com/{repo}/main/{FEED_FILE}"
    print(f"feed: {feed_url} ({len(urls)} platforms)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
