#!/usr/bin/env python3
"""QA H13-b — prove the Claude UI mockup persists its theme choice.

Drives the real device through adb, reading positions out of the mockup's own
DOM instead of guessing coordinates.

Why not hardcoded taps: the accent palette has no "海盐绿" — the real keys are
clay / clay-bright / sage / indigo / ochre / wisteria / teal. Tapping a guessed
coordinate hit nothing and looked like a failed theme switch, when in fact the
picker was closed and no tap landed at all.

Also asserts via logcat that localStorage never threw — the previous build used
file:// (an opaque origin) where localStorage is unavailable, and cds.js swallowed
the failure, so "theme resets on reopen" was indistinguishable from a render bug.

Usage: theme_persist_test.py [accent_name]
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.agents.pokeclaw"
# ASCII-only match strings on purpose: PowerShell cannot pass non-ASCII argv or
# env values on this machine (GBK), which silently turns Chinese into mojibake
# and makes every lookup fail. "Claude UI" is the ASCII prefix of the entry row.
ENTRY = "Claude UI"
NUM = re.compile(r"-?\d+")
# The accent swatches carry their hex in .swatch-hex — a stable ASCII handle for
# a palette whose Chinese names cannot survive the shell.
ACCENTS = {
    "#d97757": "clay",        # 陶土橙 (default)
    "#6f9068": "sage",        # 鼠尾草绿
    "#7b93c4": "indigo",      # 靛蓝
    "#5c918e": "teal",        # 深青
}
WANT_HEX = os.environ.get("CDS_HEX", "#6f9068")
WANT_NAME = ACCENTS.get(WANT_HEX, WANT_HEX)


def sh(*args: str) -> bytes:
    return subprocess.run(["adb", "shell", *args],
                          capture_output=True, check=False).stdout


def text() -> str:
    return sh("dumpsys", "window").decode("utf-8", "replace")


def focus() -> str:
    m = re.search(r"mCurrentFocus=Window\{[^}]*\}", text())
    return m.group(0) if m else "?"


def in_preview() -> bool:
    return "ClaudeUiPreviewActivity" in focus()


def dump():
    sh("uiautomator", "dump", "/sdcard/tp.xml")
    return sh("cat", "/sdcard/tp.xml").decode("utf-8", "replace")


def center(bounds: str):
    nums = [int(v) for v in NUM.findall(bounds or "")]
    if len(nums) != 4 or nums == [0, 0, 0, 0]:
        return None
    x1, y1, x2, y2 = nums
    return (x1 + x2) // 2, (y1 + y2) // 2


def nodes():
    """[(label, (cx, cy))] for every laid-out node, text or content-desc."""
    try:
        root = ET.fromstring(dump())
    except ET.ParseError:
        return []
    out = []
    for n in root.iter("node"):
        label = (n.get("text") or "").strip() or (n.get("content-desc") or "").strip()
        pt = center(n.get("bounds"))
        if label and pt:
            out.append((label, pt))
    return out


def retry_find(needle: str, tries: int = 6):
    """uiautomator often grabs a background window; retry rather than trust once."""
    for _ in range(tries):
        for label, pt in nodes():
            if needle in label:
                return pt
        time.sleep(1)
    return None


def tap(pt) -> None:
    if pt:
        sh("input", "tap", str(pt[0]), str(pt[1]))
        time.sleep(2)


def shot(name: str) -> None:
    sh("screencap", "-p", f"/sdcard/{name}.png")


def logcat_has_storage_error() -> bool:
    out = sh("logcat", "-d").decode("utf-8", "replace")
    return "[cds] localStorage" in out


def read_accent_label() -> str:
    """The picker's own label line, e.g. '米黄 鼠尾草绿'."""
    for label, _ in nodes():
        if WANT_HEX in label:
            return label
    return ""


def main() -> int:
    sh("logcat", "-c")
    print(f"target accent: {WANT_NAME} ({WANT_HEX})")

    if not in_preview():
        print("FAIL: not in the preview to start")
        return 1

    # 1. open the theme picker
    toggle = retry_find("切换深浅色") or retry_find("主题")
    if not toggle:
        print("FAIL: theme toggle not found")
        return 1
    print(f"opened picker at {toggle}")
    tap(toggle)
    shot("tp1")

    # 2. pick the accent
    swatch = retry_find(WANT_HEX)
    if not swatch:
        print(f"FAIL: accent {WANT_NAME} ({WANT_HEX}) not found in picker")
        return 1
    print(f"tapped {WANT_NAME} at {swatch}")
    tap(swatch)
    shot("tp2")

    if not in_preview():
        print("FAIL: left the preview while picking a colour")
        return 1

    # 3. close the picker so it does not swallow the first BACK
    toggle2 = retry_find("切换深浅色")
    tap(toggle2)
    shot("tp3")

    # 4. close and reopen
    sh("input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    sh("input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    print(f"after close: {focus()}")

    entry = retry_find(ENTRY)
    if not entry:
        print("FAIL: could not find the entry point again")
        return 1
    tap(entry)
    print(f"after reopen: {focus()}")

    if not in_preview():
        print("FAIL: did not reopen the preview")
        return 1

    # 5. the accent must have survived — read it from the page's own label
    time.sleep(2)
    persisted = read_accent_label()
    shot("tp4")

    storage_errors = logcat_has_storage_error()

    print(f"\npersisted label: {persisted!r}")
    print(f"localStorage errors in logcat: {storage_errors}")

    ok = bool(persisted) and not storage_errors
    print("\nVERDICT:", "PASS — theme survived the reopen" if ok
          else "FAIL — theme did not persist")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
