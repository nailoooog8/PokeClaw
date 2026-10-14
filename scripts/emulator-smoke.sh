#!/usr/bin/env bash
# Emulator smoke test runner invoked by .github/workflows/emulator-matrix.yml
# Each line of the workflow's `script:` block is interpreted as an
# independent `sh -c` call, which breaks multi-line shell (variable
# assignments, if/then/fi). So we put the actual logic in this file
# and let the workflow call it as one line.
#
# Usage: ./scripts/emulator-smoke.sh <api-level>
#
# Two hardening rules, both added after run 38408447192 (2026-10-13) hung the
# API 29 job for 30 minutes:
#
#  1. Do NOT trust the emulator's "Boot completed" log line. With a cached AVD
#     snapshot the emulator restores memory state and reports boot completion in
#     ~4s while framework services are still unregistered -- the run died with
#     "ServiceManager$ServiceNotFoundException: No service published for: input".
#     We now poll `getprop sys.boot_completed` ourselves.
#  2. Every adb call is wrapped in `timeout` (ADB_TIMEOUT, default 60s). An
#     unreachable device used to block indefinitely; the job only ended because
#     the workflow's timeout-minutes fired, which reports the run as
#     "cancelled" instead of "failed" and produces no artifacts.

set -euo pipefail

API_LEVEL="${1:-unknown}"
ADB_TIMEOUT="${ADB_TIMEOUT:-60}"
BOOT_WAIT_TRIES="${BOOT_WAIT_TRIES:-40}"   # 40 * 5s = 200s max boot wait
PKG="io.agents.pokeclaw"
# Correct component is io.agents.pokeclaw/.ui.splash.SplashActivity
# (the .ui.splash. path is the actual class location; earlier rev had a stale
# com.apk.claw.android.ui.splash.SplashActivity name from a pre-rename build).
ACTIVITY="io.agents.pokeclaw/.ui.splash.SplashActivity"

adb_t() { timeout "$ADB_TIMEOUT" adb "$@"; }

fail() { echo "::error::$1"; exit 1; }

# Leave something in the artifact even when we bail out early, so a failed run
# is still diagnosable. The workflow uploads logcat-*.txt and screen-*.png.
dump_diagnostics() {
  echo "::group::Dump diagnostics (failure path)"
  adb_t logcat -d -t 300 > "logcat-api${API_LEVEL}.txt" 2>/dev/null || true
  adb_t shell screencap -p "/sdcard/screen-api${API_LEVEL}.png" 2>/dev/null || true
  adb_t pull "/sdcard/screen-api${API_LEVEL}.png" . >/dev/null 2>&1 || true
  echo "::endgroup::"
}
trap 'rc=$?; if [ "$rc" -ne 0 ]; then dump_diagnostics; fi' EXIT

echo "::group::APK info"
echo "Searching for APK under apk/ ..."
find apk -type f -name '*.apk' || echo "no .apk found anywhere under apk/"
APK="$(find apk -type f -name '*.apk' | head -1)"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "::error::No APK found under apk/. download-artifact may have placed it elsewhere or upload failed."
  ls -laR apk/ || true
  exit 1
fi
echo "Using APK: $APK ($(stat -c%s "$APK") bytes)"
echo "::endgroup::"

# Must run before any other adb call: without a fully booted framework every
# later command either hangs or throws ServiceNotFoundException.
echo "::group::Wait for framework boot (sys.boot_completed)"
tries="$BOOT_WAIT_TRIES"
while [ "$tries" -gt 0 ]; do
  booted="$(adb_t shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ' || true)"
  if [ "$booted" = "1" ]; then
    echo "sys.boot_completed=1"
    echo "::endgroup::"
    break
  fi
  tries=$((tries - 1))
  if [ "$tries" -le 0 ]; then
    echo "::endgroup::"
    adb_t shell getprop 2>/dev/null | head -20 || true
    fail "sys.boot_completed never became 1 after $((BOOT_WAIT_TRIES * 5))s -- framework did not finish booting"
  fi
  sleep 5
done

echo "::group::Device info"
echo "release: $(adb_t shell getprop ro.build.version.release)"
echo "sdk:     $(adb_t shell getprop ro.build.version.sdk)"
echo "model:   $(adb_t shell getprop ro.product.model)"
echo "::endgroup::"

echo "::group::Install APK"
adb_t install -r -t "$APK" || fail "adb install failed"
echo "::endgroup::"

echo "::group::Verify package installed"
adb_t shell pm list packages | grep "$PKG" || fail "Package $PKG not installed"
echo "::endgroup::"

echo "::group::Launch app"
adb_t logcat -c
adb_t shell am start -W -n "$ACTIVITY" \
  || adb_t shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 \
  || fail "Could not launch $ACTIVITY"
echo "::endgroup::"

echo "::group::Wait for app to settle"
sleep 8
echo "::endgroup::"

echo "::group::Verify process running"
PID="$(adb_t shell pidof "$PKG" || true)"
if [ -z "$PID" ]; then
  echo "::error::App crashed on launch! No process found for $PKG"
  adb_t logcat -d -b crash | head -50 > "logcat-crash-buffer.txt" || true
  exit 1
fi
echo "App is running with PID: $PID"
echo "::endgroup::"

echo "::group::Check for crashes in logcat"
adb_t logcat -d -b crash | head -50 > "logcat-crash-buffer.txt"
if grep -q "FATAL EXCEPTION" "logcat-crash-buffer.txt"; then
  echo "::error::FATAL EXCEPTION found in crash buffer"
  cat "logcat-crash-buffer.txt"
  exit 1
fi
echo "No fatal exceptions in crash buffer."
echo "::endgroup::"

echo "::group::Capture logcat artifact"
adb_t logcat -d > "logcat-full-api${API_LEVEL}.txt"
adb_t shell screencap -p "/sdcard/screen-api${API_LEVEL}.png"
adb_t pull "/sdcard/screen-api${API_LEVEL}.png" .
echo "::endgroup::"

echo "::group::Smoke test summary"
echo "API Level: ${API_LEVEL}"
echo "PASS: app installs, launches, no crash, process alive after 8s"
echo "::endgroup::"
