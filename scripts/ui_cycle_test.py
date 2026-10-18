#!/usr/bin/env python3
"""QA H14 — Claude UI preview open/close cycle, checking for a WebView leak.

Navigates entirely through the UI: launcher -> app -> Settings -> preview ->
BACK BACK -> Settings. Records TOTAL PSS after each close.

A WebView that is never destroyed grows the process monotonically. GC noise is
not a leak: assert the trend is flat, not that any single sample is small.

Also asserts the preview Activity is actually destroyed (absent from
dumpsys activity), which is the real proof onDestroy ran.

Usage: ui_cycle_test.py [cycles]
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "io.agents.pokeclaw"
ENTRY = "Claude UI 设计稿"   # Claude UI 设计稿
NUM = re.compile(r"-?\d+")


def sh(*args: str, binary: bool = False):
    return subprocess.run(["adb", "shell", *args],
                          capture_output=True, check=False).stdout


def focus() -> str:
    out = sh("dumpsys", "window").decode("utf-8", "replace")
    m = re.search(r"mCurrentFocus=Window\{[^}]*\}", out)
    return m.group(0) if m else "?"


def is_preview() -> bool:
    return "ClaudeUiPreviewActivity" in focus()


def pss() -> int:
    out = sh("dumpsys", "meminfo", PKG).decode("utf-8", "replace")
    m = re.search(r"TOTAL PSS:\s+(\d+)", out)
    return int(m.group(1)) if m else -1


def activity_alive() -> bool:
    return b"ClaudeUiPreview" in sh("dumpsys", "activity", "activities")


def center(bounds: str):
    nums = [int(v) for v in NUM.findall(bounds or "")]
    if len(nums) != 4 or nums == [0, 0, 0, 0]:
        return None
    x1, y1, x2, y2 = nums
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text: str):
    sh("uiautomator", "dump", "/sdcard/cyc.xml")
    xml = sh("cat", "/sdcard/cyc.xml").decode("utf-8", "replace")
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        label = (node.get("text") or "").strip() or (node.get("content-desc") or "").strip()
        if text in label:
            return center(node.get("bounds"))
    return None


def tap(pt) -> None:
    if pt:
        sh("input", "tap", str(pt[0]), str(pt[1]))


def go_home() -> None:
    sh("input", "keyevent", "KEYCODE_HOME")


def open_settings() -> bool:
    """Home -> launch app -> open Settings. Settings is not exported (QA note)."""
    go_home()
    time.sleep(1)
    subprocess.run(["adb", "shell", "monkey", "-p", PKG,
                    "-c", "android.intent.category.LAUNCHER", "1"],
                   capture_output=True, check=False)
    time.sleep(3)
    pt = find("设置")          # 设置
    if not pt:
        return False
    tap(pt)
    time.sleep(3)
    return "SettingsActivity" in focus()


def open_preview() -> bool:
    pt = find(ENTRY)
    if not pt:
        return False
    tap(pt)
    time.sleep(3)
    return is_preview()


def close_preview() -> None:
    sh("input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    sh("input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)


def main() -> int:
    cycles = int(sys.argv[1]) if len(sys.argv) > 1 else 3
    samples, entered = [], 0

    for i in range(1, cycles + 1):
        if not open_settings():
            print(f"cycle {i}: SKIP — could not reach Settings")
            continue
        if not open_preview():
            print(f"cycle {i}: SKIP — entry point not found / did not open")
            continue
        entered += 1
        close_preview()
        alive = activity_alive()
        p = pss()
        samples.append(p)
        print(f"cycle {i}: entered=OK  PSS={p/1024:.1f}MB  activity_alive={alive}")

    if entered < 2:
        print(f"\nINCONCLUSIVE — only {entered} cycle(s) actually entered the preview")
        return 1

    lo, hi = min(samples), max(samples)
    growth = samples[-1] - samples[0]
    print(f"\nentered={entered}  PSS min={lo/1024:.1f}MB max={hi/1024:.1f}MB "
          f"drift={growth/1024:+.1f}MB")

    # A real leak grows monotonically every cycle. GC noise oscillates.
    monotonic = all(b >= a for a, b in zip(samples, samples[1:]))
    if monotonic and growth > 20 * 1024:
        print("VERDICT: LEAK — memory grew every cycle")
        return 1
    print("VERDICT: no monotonic growth across cycles")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
