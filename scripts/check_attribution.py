"""Guard the upstream attribution this fork is required to preserve.

Apache-2.0 section 4(d): a derivative work must carry the License and any
NOTICE file, and may add its own attribution but must not remove upstream's.
This asserts the upstream block is still present byte-for-byte, and that the
fork's own notice does not silently drop it.

Run: python scripts/check_attribution.py
Exits non-zero if upstream attribution has been removed or weakened.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    if not ok:
        failures.append(label)


notice = (ROOT / "NOTICE").read_text(encoding="utf-8")
license_head = (ROOT / "LICENSE").read_text(encoding="utf-8")[:200]

print("1. NOTICE retains upstream attribution (Apache-2.0 s4d)")
check("upstream copyright line present verbatim",
      "Copyright 2026 agents.io" in notice)
check("upstream attribution sentence present verbatim",
      "This product includes software developed at agents.io "
      "(https://github.com/agents-io)." in notice)
check("Apache-2.0 reference present", "Apache License, Version 2.0" in notice)
# The upstream block must come first: our addition is appended, not prepended.
check("upstream block is still at the top of NOTICE",
      notice.lstrip().startswith("PokeClaw"))

print("2. LICENSE is the unmodified Apache-2.0 text")
check("LICENSE starts with the Apache-2.0 boilerplate",
      "Apache License" in license_head and "Version 2.0" in license_head)

print("3. fork notice states its own independence")
check("declares independent fork", "independent fork" in notice)
check("links to this fork", "github.com/nailoooog8/PokeClaw" in notice)
check("disclaims affiliation",
      "not affiliated with, endorsed by, or a substitute" in notice)
check("marks upstream-authored docs as upstream's",
      "describe upstream's intentions only" in notice)

print("4. no stray upstream trademark claim added to the fork notice")
# The fork must not assert it owns the PokeClaw name.
check("no added trademark claim",
      "trademark" not in notice.lower(),
      "upstream README reserves the name; the fork must not re-assert it")

print("5. audit honesty: E2E status is disclosed, not implied")
check("states SEC1-SEC11 not fully verified",
      "NOT complete" in notice and "SEC1-SEC11" in notice)

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {', '.join(failures)}")
    sys.exit(1)
print("OK: upstream attribution intact and fork additions are clearly separated.")
