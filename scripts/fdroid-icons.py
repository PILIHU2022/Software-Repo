#!/usr/bin/env python3
"""Keep the F-Droid index icons in sync with fdroid/icons-src/.

`fdroid update` extracts icons from APKs, but the apps here use adaptive
(XML) launcher icons, which fdroidserver cannot rasterize.  The per-app
graphics in repo/<package>/<locale>/icon.png fix index-v2; this script adds the
legacy index-v1 `icon` field (and `--check` verifies both indexes).

Usage:
  scripts/fdroid-icons.py --repo fdroid/repo
  scripts/fdroid-icons.py --repo fdroid/repo --check
"""

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


def load(path):
    with path.open(encoding="utf-8") as fp:
        return json.load(fp)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def choose_icon(icons, package, preferred):
    """Return the filename of the best icons/<package>.<versionCode>.png."""
    preferred = str(preferred or "")
    if preferred:
        name = f"{package}.{preferred}.png"
        if (icons / name).is_file():
            return name

    candidates = []
    pattern = re.compile(r"^" + re.escape(package) + r"\.(\d+)\.png$")
    for path in icons.glob(f"{package}.*.png"):
        match = pattern.match(path.name)
        if match:
            candidates.append((int(match.group(1)), path.name))
    if not candidates:
        return None
    return max(candidates)[1]


def patch(repo):
    index = repo / "index-v1.json"
    if not index.is_file():
        raise SystemExit(f"missing {index}")
    data = load(index)
    icons = repo / "icons"
    changed = 0
    for app in data.get("apps", []):
        package = app.get("packageName")
        if not package:
            continue
        name = choose_icon(icons, package, app.get("suggestedVersionCode"))
        if name is None:
            print(f"    no icon for {package} (add fdroid/icons-src/{package}.png)")
            continue
        if app.get("icon") != name:
            app["icon"] = name
            changed += 1
    if changed:
        with index.open("w", encoding="utf-8") as fp:
            json.dump(data, fp, separators=(",", ":"))
        print(f"    patched {changed} index-v1 icon(s)")
    else:
        print("    index-v1 icons already current")
    return changed


def check(repo):
    errors = []
    v2_path = repo / "index-v2.json"
    v1_path = repo / "index-v1.json"
    if not v2_path.is_file() or not v1_path.is_file():
        print("missing index-v1.json or index-v2.json", file=sys.stderr)
        return 1

    v2 = load(v2_path)
    for package, entry in v2.get("packages", {}).items():
        icon = entry.get("metadata", {}).get("icon")
        if not icon:
            errors.append(f"{package}: index-v2 has no icon")
            continue
        for locale, meta in icon.items():
            name = meta.get("name", "")
            path = repo / name.lstrip("/")
            if not path.is_file():
                errors.append(f"{package} ({locale}): missing {path}")
            elif sha256(path) != meta.get("sha256"):
                errors.append(f"{package} ({locale}): sha256 mismatch for {path}")

    v1 = load(v1_path)
    for app in v1.get("apps", []):
        package = app.get("packageName")
        name = app.get("icon")
        if not package:
            continue
        if not name:
            errors.append(f"{package}: index-v1 has no icon")
        elif not (repo / "icons" / name).is_file():
            errors.append(f"{package}: missing icons/{name}")

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"    icons OK: {len(v1.get('apps', []))} app(s)")
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    if args.check:
        return check(repo)
    patch(repo)
    return 0


if __name__ == "__main__":
    sys.exit(main())
