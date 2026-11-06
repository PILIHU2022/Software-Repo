#!/usr/bin/env python3
"""Validate a union merge of Android/Compose resource strings files.

Usage:
    scripts/validate-strings-merge.py MERGED OURS THEIRS

MERGED must parse as XML, must not contain duplicate <string name="...">
entries, and must still contain every string name present in OURS or THEIRS
(otherwise the union merge silently dropped one side).

Exit status: 0 valid, 1 invalid, 2 usage error. Reasons go to stderr so the
caller can paste them into the build log / failure issue.
"""

from __future__ import annotations

import sys
import xml.etree.ElementTree as ET


def string_names(path: str) -> list[str]:
    """Return the name= of every <string> element, in document order."""
    root = ET.parse(path).getroot()
    return [
        element.get("name")
        for element in root.iter()
        if element.tag == "string" and element.get("name")
    ]


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(
            "usage: validate-strings-merge.py MERGED OURS THEIRS",
            file=sys.stderr,
        )
        return 2

    merged_path, ours_path, theirs_path = argv[1:4]
    try:
        merged = string_names(merged_path)
        ours = set(string_names(ours_path))
        theirs = set(string_names(theirs_path))
    except ET.ParseError as exc:
        print(f"invalid XML: {exc}", file=sys.stderr)
        return 1
    except OSError as exc:
        print(f"cannot read merge input: {exc}", file=sys.stderr)
        return 1

    counts: dict[str, int] = {}
    for name in merged:
        counts[name] = counts.get(name, 0) + 1
    duplicates = sorted(name for name, count in counts.items() if count > 1)
    if duplicates:
        print(
            'duplicate <string name="..."> after merge: ' + ", ".join(duplicates),
            file=sys.stderr,
        )
        return 1

    missing = sorted((ours | theirs) - set(merged))
    if missing:
        print(
            "union merge dropped strings from one side: " + ", ".join(missing),
            file=sys.stderr,
        )
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
