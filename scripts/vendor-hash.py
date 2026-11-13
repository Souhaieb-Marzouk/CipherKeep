#!/usr/bin/env python3
"""Hash the vendored sources by exact file bytes."""
from __future__ import annotations

import hashlib
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VENDOR = ROOT / "vendor"
RECORD = ROOT / "vendor.sha256"


def digest() -> str:
    if not VENDOR.is_dir():
        raise SystemExit("vendor/ is missing")
    outer = hashlib.sha256()
    count = 0
    for path in sorted(p for p in VENDOR.rglob("*") if p.is_file()):
        relative = path.relative_to(VENDOR).as_posix().encode()
        file_hash = hashlib.sha256(path.read_bytes()).hexdigest().encode()
        outer.update(len(relative).to_bytes(8, "big"))
        outer.update(relative)
        outer.update(file_hash)
        count += 1
    if count == 0:
        raise SystemExit("vendor/ has no files")
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
