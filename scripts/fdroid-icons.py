#!/usr/bin/env python3
"""Keep the F-Droid index icons in sync with fdroid/icons-src/.

`fdroid update` extracts icons from APKs, but the apps here use adaptive
(XML) launcher icons, which fdroidserver cannot rasterize.  It can also prune
content-addressed index-v2 icons when a new package is added.  This script
restores both forms from the per-app icon sources, adds the legacy index-v1
`icon` field, and lets `--check` verify both indexes.

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


def icon_source(repo, package, locale):
    """Return the best source icon for a package and locale."""
    for path in (
        repo.parent / "icons-src" / f"{package}.png",
        repo / package / locale / "icon.png",
    ):
        if path.is_file():
            return path
    return None


def restore_index_v2_icons(repo):
    """Restore content-addressed icons that fdroid update may have pruned.

    When a new package is added, fdroidserver can drop the existing
    repo/<package>/<locale>/icon_<hash>.png files while keeping the old
    references in index-v2.json.  The index is then inconsistent until the
    files are put back, so copy the source icon to the name and hash the index
    already expects.

    index-v2.json must not be rewritten here: entry.json records its sha256 and
    `fdroid signindex` refuses a hash mismatch, so only a repository-wide
    `fdroid update` may change the index.  A source icon that no longer matches
    its index entry is therefore an error, not something to paper over.
    """
    index = repo / "index-v2.json"
    if not index.is_file():
        return 0

    data = load(index)
    changed = 0
    for package, entry in data.get("packages", {}).items():
        icon = entry.get("metadata", {}).get("icon")
        if not isinstance(icon, dict):
            continue
        for locale, meta in icon.items():
            if not isinstance(meta, dict):
                continue
            name = meta.get("name")
            expected = meta.get("sha256")
            target = repo / name.lstrip("/") if isinstance(name, str) else None
            if target is not None and target.is_file():
                if not expected or sha256(target) == expected:
                    continue

            source = icon_source(repo, package, locale)
            if source is None:
                print(f"    no icon source for {package} ({locale})")
                continue

            content = source.read_bytes()
            digest = hashlib.sha256(content).hexdigest()
            if not isinstance(name, str) or not expected or digest != expected:
                raise SystemExit(
                    f"icon source for {package} ({locale}) does not match the "
                    f"index (expected {expected}, got {digest}); regenerate "
                    "fdroid/icons-src and the repository index"
                )

            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            print(f"    restored {package} ({locale}): {target.relative_to(repo)}")
            changed += 1

    if changed:
        print(f"    restored {changed} index-v2 icon(s)")
    else:
        print("    index-v2 icons already current")
    return changed


def patch(repo):
    restore_index_v2_icons(repo)

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
            source = icon_source(repo, package, "en-US")
            code = app.get("suggestedVersionCode")
            if source is not None and code:
                name = f"{package}.{code}.png"
                target = icons / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
                print(f"    restored legacy icon {package} ({code})")
                changed += 1
                app["icon"] = name
                continue
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
