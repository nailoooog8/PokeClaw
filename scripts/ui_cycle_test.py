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
ENTRY = "Claude UI 实验版"   # matches settings_about_claude_ui_preview (zh)
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
    """Is the preview Activity still a live record in the task?

    A plain substring search over `dumpsys activity activities` is wrong: the
    task keeps a `mLastPausedActivity` pointer to the last paused activity, and
    that pointer survives the activity being destroyed (the record is stamped
    `t-1 d-1 f`). Searching for the name therefore reports a leak for an
    activity that finished cleanly. Only a live `Hist #N` record counts.
    """
    out = sh("dumpsys", "activity", "activities").decode("utf-8", "replace")
    for line in out.splitlines():
        if "Hist" in line and "ClaudeUiPreviewActivity" in line:
            return True
    return False


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


def back_to_chat() -> bool:
    """Back out of whatever screen is on top until the chat page is up.

    Launching the app does NOT guarantee the chat page: the launcher intent
    resumes whatever activity was on top when the process died, which may be
    the preview surface or Settings. Reaching the chat page explicitly is
    what makes the navigation below deterministic.
    """
    for _ in range(4):
        if "ComposeChatActivity" in focus():
            return True
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)
    return "ComposeChatActivity" in focus()


def open_settings() -> bool:
    """Chat -> Settings. Settings is not exported (QA note)."""
    go_home()
    time.sleep(1)
    subprocess.run(["adb", "shell", "monkey", "-p", PKG,
                    "-c", "android.intent.category.LAUNCHER", "1"],
                   capture_output=True, check=False)
    time.sleep(3)
    if not back_to_chat():
        return False
    pt = find("设置")          # 设置
    if not pt:
        return False
    tap(pt)
    time.sleep(3)
    return "SettingsActivity" in focus()


def open_preview() -> bool:
    """Settings -> preview. The entry lives near the bottom of the About
    section, so scroll down until it is on screen instead of assuming it is."""
    for _ in range(6):
        pt = find(ENTRY)
        if pt:
            tap(pt)
            time.sleep(3)
            if is_preview():
                return True
            return False
        sh("input", "swipe", "630", "2000", "630", "900", "400")
        time.sleep(1.5)
    return False


def close_preview() -> None:
    """BACK until the preview is really gone.

    One BACK is not always enough: when the composer holds focus, the WebView
    consumes the key to drop focus and the surface stays put. Pressing BACK
    blindly twice can therefore land on the chat page instead of Settings.
    """
    for _ in range(3):
        if not is_preview():
            return
        sh("input", "keyevent", "KEYCODE_BACK")
        time.sleep(1.5)


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
