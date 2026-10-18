#!/usr/bin/env python3
"""Dump uiautomator XML and print text nodes with tap coordinates.

Windows PowerShell mangles non-ASCII in inline one-liners, so this lives in a
file with an explicit encoding declaration instead.

Usage:  ui_probe.py [substring ...]
        ui_probe.py --tap "Claude UI"        # tap the first matching node
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# uiautomator reports bounds as "[x1,y1][x2,y2]" — bracketed, not plain CSV.
NUM = re.compile(r"-?\d+")


def center(bounds: str):
    """(cx, cy) from a "[x1,y1][x2,y2]" bounds string, or None if unusable."""
    nums = [int(v) for v in NUM.findall(bounds or "")]
    if len(nums) != 4:
        return None
    x1, y1, x2, y2 = nums
    # Compose reports [0,0][0,0] for nodes it has not laid out yet; those cannot
    # be tapped, and silently averaging them produces a tap at the screen origin.
    if x1 == 0 and y1 == 0 and x2 == 0 and y2 == 0:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def dump() -> str:
    subprocess.run(
        ["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"],
        capture_output=True, check=False,
    )
    raw = subprocess.run(
        ["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, check=False,
    ).stdout
    # uiautomator emits UTF-8; adb may hand it back as bytes.
    return raw.decode("utf-8", errors="replace")


def main() -> int:
    xml = dump()
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        print(f"parse error: {exc}", file=sys.stderr)
        return 1

    terms = [a for a in sys.argv[1:] if a != "--tap"]
    tap = "--tap" in sys.argv
    hits = 0

    # Parse the tree instead of regexing: a regex over the raw XML happily pairs a
    # node's text with the NEXT node's bounds, which silently yields (0,0) taps.
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        desc = (node.get("content-desc") or "").strip()
        label = text or desc
        if not label:
            continue
        if terms and not any(t in label for t in terms):
            continue
        point = center(node.get("bounds"))
        if point is None:
            print(f"{label!r:<58} (no bounds)")
            continue
        cx, cy = point
        hits += 1
        print(f"{label!r:<58} ({cx},{cy})")
        if tap:
            subprocess.run(["adb", "shell", "input", "tap", str(cx), str(cy)],
                           capture_output=True, check=False)
            print(f"  -> tapped ({cx},{cy})")
            break

    if not hits:
        print("no matching nodes", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
