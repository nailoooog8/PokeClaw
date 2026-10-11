#!/usr/bin/env python3
"""Find tappable nodes by text/desc in a uiautomator dump and emit tap coords."""
import re
import sys

path = sys.argv[1]
keys = sys.argv[2:] or ["设置"]

xml = open(path, encoding="utf-8", errors="replace").read()

pat = re.compile(
    r'<node[^>]*?(?:text|content-desc)="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)

seen = set()
for m in pat.finditer(xml):
    label = m.group(1)
    if not label or not any(k in label for k in keys):
        continue
    x1, y1, x2, y2 = map(int, m.groups()[1:])
    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
    key = (label, cx, cy)
    if key in seen:
        continue
    seen.add(key)
    print(f"{label!r} -> tap {cx} {cy}   bounds=[{x1},{y1}][{x2},{y2}]")
