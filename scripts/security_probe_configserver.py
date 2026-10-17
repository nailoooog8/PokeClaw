#!/usr/bin/env python3
"""
Adversarial probe for PokeClaw's LAN ConfigServer (E2E, on-device).

Simulates a real attacker on the same Wi-Fi:
  A1  unauthenticated secret harvest
  A2  6-digit PIN brute force -> does it lock out?
  A3  Host-header / DNS-rebinding pre-auth
  A4  path traversal on /api/debug/file (debug builds)

Usage:  python security_probe_configserver.py [base_url] [pin]
        default base_url = http://127.0.0.1:9527   (via adb forward tcp:9527 tcp:9527)
        pin: the real 6-digit console PIN, shown in Settings -> 局域网配置.
             Required to verify the brute-force lockout (see A2).
"""
import json
import sys
import urllib.request
import urllib.error

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:9527"
PIN = sys.argv[2] if len(sys.argv) > 2 else None
TIMEOUT = 5


def req(path, method="GET", headers=None, body=None):
    url = BASE + path
    data = body.encode() if body else None
    r = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=TIMEOUT) as resp:
            return resp.status, resp.read().decode(errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(errors="replace")
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"


def hr(title):
    print("\n" + "=" * 68)
    print(title)
    print("=" * 68)


def looks_like_secret(value):
    """A masked/empty value is not a leak."""
    if not value:
        return False
    v = value.strip()
    if "*" in v:
        return False
    return len(v) >= 8


# ---------------------------------------------------------------- A1
hr("A1  Unauthenticated secret harvest (no PIN supplied)")
leaks = []
for path, fields in [
    ("/api/channels", ["discordBotToken", "telegramBotToken", "wechatBotToken",
                       "qqBotToken", "douyinToken"]),
    ("/api/llm", ["llmApiKey"]),
]:
    status, body = req(path)
    print(f"  GET {path:16} -> HTTP {status}")
    if status != 200:
        print(f"      blocked: {body[:90]}")
        continue
    try:
        data = json.loads(body).get("data", {})
    except Exception as e:
        print(f"      unparseable: {e}")
        continue
    for f in fields:
        v = data.get(f)
        state = "LEAK" if looks_like_secret(v) else "masked/empty"
        print(f"      {f:20} = {state}")
        if looks_like_secret(v):
            leaks.append((path, f))

if leaks:
    print(f"\n  >>> A1 FAIL: {len(leaks)} secret(s) returned in plaintext")
else:
    print("\n  >>> A1 PASS: no plaintext secret served without credentials")

# ---------------------------------------------------------------- A2
hr("A2  PIN brute force (expect lockout, not 10^6 attempts)")
# ConfigServer answers a locked-out client with the same 401 body as a plain
# auth failure -- there is no 429/Retry-After. So "did we get a 429" is NOT a
# valid lockout signal, and asserting on it reported a false FAIL against a
# working guard. The guard's real contract is: once locked, the server stops
# comparing the PIN entirely, so even the CORRECT pin is refused. Probe that
# directly, which requires knowing the pin -> pass it in as argv[2].
#
# Side effect: this leaves the probing client locked out with an escalating
# backoff (30s << strikes, capped at 30 min). BruteForceGuard is in-memory
# only, so `adb shell am force-stop io.agents.pokeclaw` clears it; re-enable
# LAN config in Settings afterwards. Run A2 last if you care about A1/A3/A4.

if PIN is None:
    print("  no --pin supplied; sending wrong guesses only")
    for i in range(12):
        req("/api/channels", headers={"X-Config-PIN": f"{i % 10}{i % 7}{i % 9}00"})
    print("\n  >>> A2 INCONCLUSIVE: rerun with the correct pin to detect the")
    print("      lockout. Asserting on 429/403 is wrong -- a locked client gets 401.")
else:
    # Baseline: correct pin must work before we start guessing.
    base_status, _ = req("/api/channels", headers={"X-Config-PIN": PIN})
    print(f"  baseline (correct pin)  -> HTTP {base_status}")

    # Probe in batches. You cannot interleave a correct-pin request after every
    # single wrong guess: a successful auth calls onSuccess(), which deletes the
    # whole entry, so the failure counter resets to 0 and the lockout threshold
    # is unreachable. Correct PIN is therefore probed once per batch of
    # MAX_FAILURES (5) guesses, and only while it still succeeds.
    locked_at = None
    wrong = 0
    while wrong < 60:
        for i in range(5):
            wrong += 1
            req("/api/channels", headers={"X-Config-PIN": f"{(i + 1) % 10}{(i + 7) % 10}{(i + 3) % 10}00"})
        st, _ = req("/api/channels", headers={"X-Config-PIN": PIN})
        if st != 200:
            locked_at = wrong
            break

    print(f"  wrong guesses sent      -> {locked_at if locked_at else '>60'}")
    print(f"  correct pin after that  -> {'REFUSED (locked out)' if locked_at else 'still HTTP 200'}")
    if locked_at and locked_at <= 20:
        print(f"\n  >>> A2 PASS: locked out after {locked_at} wrong guesses.")
        print("      The correct pin was refused too, so the server stopped")
        print("      comparing -- a brute force gains no information per attempt.")
    else:
        print("\n  >>> A2 FAIL: correct pin still accepted after 60 wrong")
        print("      guesses -- no effective lockout, PIN is brute-forceable.")

# ---------------------------------------------------------------- A3
hr("A3  DNS-rebinding pre-auth (evil Host header)")
for host in ["evil.example.com", "attacker.com", "127.0.0.1.evil.com"]:
    status, body = req("/api/channels", headers={"Host": host})
    blocked = status in (400, 403) or "Invalid Host" in body
    print(f"  Host={host:24} -> HTTP {status}  {'BLOCKED' if blocked else 'PASSED THROUGH'}")

status, _ = req("/api/channels", headers={"Origin": "http://evil.example.com"})
print(f"  Origin=evil.example.com        -> HTTP {status}")

# ---------------------------------------------------------------- A4
hr("A4  Path traversal on /api/debug/file (debug builds only)")
for probe in ["/api/debug/file?path=/etc/passwd",
              "/api/debug/file?path=../../../../etc/passwd",
              "/api/debug/file?path=/data/data/io.agents.pokeclaw/databases/x"]:
    status, body = req(probe)
    leaked = status == 200 and ("root:" in body or len(body) > 200)
    print(f"  {probe[:52]:54} -> HTTP {status} {'LEAK' if leaked else 'blocked'}")
if not req("/api/debug/tools")[0] == 200:
    print("\n  (release build: debug endpoints correctly refused — traversal not reachable)")

# ---------------------------------------------------------------- summary
hr("SUMMARY")
print("  Re-run against a debug build on the LAN to exercise A2/A4 fully.")
print("  A1 and A3 above are the findings that matter on a real network.")
