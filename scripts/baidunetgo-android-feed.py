#!/usr/bin/env python3
"""Write the BaiduNetGo Android client's update feed, fdroid/baidunetgo/update.json.

Usage:
    scripts/baidunetgo-android-feed.py --dist DIR --tag TAG --repo OWNER/NAME \
        [--out FILE] [--notes FILE] [--apk-url URL] [--fdroid URL]

The workflow runs this once per successful Android build, after it uploads the
signed APK to a GitHub release: the feed names the newest versionCode and
versionName, says what changed, and points at the release asset. The Android
app's updater is built against exactly this document shape:

    {"versionCode": <int>, "versionName": "<string>", "notes": "<string>",
     "apk": "<absolute URL of the latest APK>",
     "fdroid": "https://software.sparkzh.top/repo?p=com.pengstem.baidunetgo"}

--dist is the build output directory; the build-info.txt inside it (written by
scripts/build-baidunetgo-android.sh) supplies version_code, version_name and
the APK file name. The APK URL defaults to that file's asset on the GitHub
release --tag, the same convention the desktop feed uses for its installers.
--out defaults to fdroid/baidunetgo/update.json, which deploy-pages.yml
publishes alongside the F-Droid repository: the canonical feed URL is
https://software.sparkzh.top/baidunetgo/update.json.

Standard library only. Exit status: 0 wrote the feed, 1 error (message on
stderr, prefixed "baidunetgo-android-feed: ").
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
# deploy-pages.yml publishes the whole committed fdroid/ directory.
FEED_FILE = "fdroid/baidunetgo/update.json"

# The canonical URL the Android updater checks (the Pages custom domain from
# fdroid/CNAME; the pilihu2022.github.io Pages URL redirects there).
FEED_URL = "https://software.sparkzh.top/baidunetgo/update.json"

# Where F-Droid lists the app (the repo page filtered to this package).
FDROID_URL = "https://software.sparkzh.top/repo?p=com.pengstem.baidunetgo"


def die(message: str) -> NoReturn:
    """Report one error the way the workflow expects and stop."""
    print(f"baidunetgo-android-feed: {message}", file=sys.stderr)
    raise SystemExit(1)


class FeedParser(argparse.ArgumentParser):
    """Report bad arguments like any other error, not argparse's own way."""

    def error(self, message: str) -> NoReturn:
        self.print_usage(sys.stderr)
        die(message)


def read_build_info(path: str) -> dict[str, str]:
    """Read build-info.txt as key=value fields; a missing file is fatal here."""
    fields: dict[str, str] = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                key, separator, value = line.partition("=")
                if separator:
                    fields[key.strip()] = value.strip()
    except FileNotFoundError:
        die(f"no build-info.txt in {os.path.dirname(path) or '.'}: {path}")
    except OSError as exc:
        die(f"cannot read {path}: {exc}")
    return fields


def synthesized_notes(fields: dict[str, str]) -> str:
    """Compose the notes from build-info.txt when no --notes file was given.

    Every field is optional: a hand-made dist may have no build-info.txt and
    an older build may not record one of the fields, so missing pieces drop
    out of the sentence instead of failing the feed.
    """
    parts = ["社区修复版（Android）"]
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
        prog="baidunetgo-android-feed.py",
        description="Write the BaiduNetGo Android update feed for one build.",
        allow_abbrev=False,
    )
    parser.add_argument("--dist", required=True, help="build output directory with build-info.txt")
    parser.add_argument("--tag", required=True, help="release tag holding the APK asset")
    parser.add_argument("--repo", required=True, help="OWNER/NAME publishing the release")
    parser.add_argument(
        "--out",
        default=FEED_FILE,
        help=f"feed file to write (default: {FEED_FILE})",
    )
    parser.add_argument(
        "--notes",
        help="markdown file with the changelog (default: built from build-info.txt)",
    )
    parser.add_argument(
        "--apk-url",
        help="absolute APK download URL (default: the build-info APK on the --tag release)",
    )
    parser.add_argument(
        "--fdroid",
        default=FDROID_URL,
        help=f"F-Droid listing URL (default: {FDROID_URL})",
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
    fdroid = args.fdroid.strip()
    if not fdroid.startswith("https://"):
        die(f"--fdroid must be an https URL, got {args.fdroid!r}")

    fields = read_build_info(os.path.join(dist, "build-info.txt"))

    version_code = fields.get("version_code", "")
    if not version_code.isdigit():
        die(f"build-info.txt has no usable version_code: {version_code!r}")
    version_name = fields.get("version_name", "")
    if not version_name:
        die("build-info.txt has no version_name")
    apk_name = fields.get("apk", "")
    if not apk_name:
        die("build-info.txt has no apk= line")

    apk_url = (args.apk_url or "").strip()
    if not apk_url:
        quoted_tag = urllib.parse.quote(tag, safe="")
        quoted_name = urllib.parse.quote(apk_name, safe="")
        apk_url = f"https://github.com/{repo}/releases/download/{quoted_tag}/{quoted_name}"

    notes = read_notes_file(args.notes) if args.notes else ""
    if not notes:
        notes = synthesized_notes(fields)

    write_feed(
        os.path.abspath(args.out),
        {
            "versionCode": int(version_code),
            "versionName": version_name,
            "notes": notes,
            "apk": apk_url,
            "fdroid": fdroid,
        },
    )

    print(f"feed: {FEED_URL} (versionCode {version_code})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
