#!/usr/bin/env bash
# QA CI1 harness test for scripts/emulator-smoke.sh.
#
# Two scenarios, because "capture failure must not fail the run" is only half
# the contract -- if making capture non-fatal also silenced the real assertions,
# the test would be worthless.
#
#   Scenario A  healthy app + hanging `adb logcat -d`  -> MUST exit 0
#   Scenario B  app process is dead                     -> MUST exit non-zero
#
# Usage: bash build/smoke_harness_test.sh <path-to-emulator-smoke.sh>

set -uo pipefail

SMOKE="${1:-scripts/emulator-smoke.sh}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"

# $1 = pidof answer ("" means app not running), $2 = "hang" | "ok"
write_fake_adb() {
  local pid_answer="$1" logcat_mode="$2"
  cat > "$WORK/bin/adb" <<FAKE
#!/usr/bin/env bash
args="\$*"
case "\$args" in
  *"getprop sys.boot_completed"*)       echo 1; exit 0 ;;
  *"getprop ro.build.version.release"*) echo 16; exit 0 ;;
  *"getprop ro.build.version.sdk"*)     echo 36; exit 0 ;;
  *"getprop ro.product.model"*)         echo sdk_gphone64_x86_64; exit 0 ;;
  "install"*)                           echo "Performing Streamed Install"; echo Success; exit 0 ;;
  *"pm list packages"*)                 echo "package:io.agents.pokeclaw"; exit 0 ;;
  *"am start"*)                         echo "Status: ok"; exit 0 ;;
  *"pidof"*)                            printf '%s' '${pid_answer}'; exit 0 ;;
  *"logcat -d -b crash"*)               exit 0 ;;
  *"logcat -d -t"*)                     echo "trap line"; exit 0 ;;
  "logcat -d")
    if [ "${logcat_mode}" = "hang" ]; then
      printf '10-19 10:55:58.977 561 985 I Telecom : PhoneAccountRegistrar: getS'
      sleep 999
    fi
    echo "log line"; exit 0
    ;;
  "screencap"*)                         exit 0 ;;
  "pull"*)                              exit 0 ;;
  *"shell getprop"*)                    echo "sys.boot_completed=1"; exit 0 ;;
  *)                                    exit 0 ;;
esac
FAKE
  chmod +x "$WORK/bin/adb"
}

run_scenario() {
  local pid_answer="$1" logcat_mode="$2"
  write_fake_adb "$pid_answer" "$logcat_mode"
  rm -rf "$WORK/repo"; mkdir -p "$WORK/repo/apk"
  : > "$WORK/repo/apk/app-debug.apk"
  cp "$SMOKE" "$WORK/repo/emulator-smoke.sh"
  ( cd "$WORK/repo" && PATH="$WORK/bin:$PATH" ADB_TIMEOUT=3 BOOT_WAIT_TRIES=2 \
      bash emulator-smoke.sh 35 2>&1 )
}

fail=0

# ---- Scenario A: healthy app, capture hangs -> must exit 0 -----------------
out="$(run_scenario "2410" "hang")"; rc=$?
echo "===== Scenario A: healthy app + hanging logcat ====="
echo "$out" | tail -8
echo "exit code: $rc"
if [ "$rc" -eq 0 ] && echo "$out" | grep -q "^PASS: app installs"; then
  echo "  -> OK: reached PASS, capture failure did not fail the run"
else
  echo "  -> BAD: expected exit 0 + PASS summary, got rc=$rc"
  fail=1
fi

# ---- Scenario B: app process dead -> must exit non-zero --------------------
out="$(run_scenario "" "ok")"; rc=$?
echo
echo "===== Scenario B: app process dead ====="
echo "$out" | tail -6
echo "exit code: $rc"
if [ "$rc" -ne 0 ]; then
  echo "  -> OK: still fails when the app is genuinely broken"
else
  echo "  -> BAD: assertion was silenced by the fix -- test is worthless"
  fail=1
fi

echo
if [ "$fail" -eq 0 ]; then echo "OVERALL: PASS"; else echo "OVERALL: FAIL"; fi
exit "$fail"
