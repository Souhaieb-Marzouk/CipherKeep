#!/usr/bin/env python3
"""Hash the committed vendor tree by Git blob identity.

This is independent of checkout line endings and filesystem ordering.
"""
from __future__ import annotations

import hashlib
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RECORD = ROOT / "vendor.sha256"


def digest() -> str:
    raw = subprocess.check_output(
        ["git", "ls-tree", "-r", "-z", "HEAD", "--", "vendor"],
        cwd=ROOT,
    )
    outer = hashlib.sha256()
    count = 0
    for record in raw.split(b"\0"):
        if not record:
            continue
        meta, path = record.split(b"\t", 1)
        _mode, kind, blob = meta.split(b" ")
        if kind != b"blob":
            continue
        relative = path[len(b"vendor/"):]
        outer.update(relative)
        outer.update(b"\0")
        outer.update(blob)
        outer.update(b"\n")
        count += 1
    if count == 0:
        raise SystemExit("vendor/ is missing from the commit")
    return outer.hexdigest()


def main() -> int:
    actual = digest()
    if "--check" in sys.argv:
        expected = RECORD.read_text(encoding="utf-8").strip()
        if expected != actual:
            print(f"vendor hash mismatch: {actual} != {expected}", file=sys.stderr)
            return 1
        print(f"vendor hash: {actual}")
        return 0
    RECORD.write_text(f"{actual}\n", encoding="utf-8")
    print(f"vendor hash: {actual}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
