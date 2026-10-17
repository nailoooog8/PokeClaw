"""Verify the DCO signature phrase is consistent across config and docs.

The signature checker in contributor-assistant/github-action v2.6.1
(src/pullrequest/signatureComment.ts:65-69) hard-codes a lowercase leading "i"
AND a lowercase "dco", with no case-insensitive flag. The conventional
"I have read the DCO document and I hereby sign the DCO" is therefore silently
rejected — only the fully lowercase form matches. This asserts that every place
we tell a contributor what to type actually matches that regex, so the prompt,
CLA.md and CONTRIBUTING.md cannot drift apart.

Run: python scripts/check_dco_consistency.py
Exits non-zero on any mismatch.
"""

import re
import sys
from pathlib import Path

import yaml

# Verbatim from upstream src/pullrequest/signatureComment.ts, case 'true'.
UPSTREAM_DCO_RE = re.compile(
    r"^.*i \s*have \s*read \s*the \s*dco \s*document \s*and \s*i \s*hereby \s*sign \s*the \s*dco.*$"
)

CANONICAL = "i have read the dco document and i hereby sign the dco"
# Any capitalisation of the phrase would bait contributors into a rejected comment.
# Matching the canonical sentence itself is expected, so variants are found by
# collecting every casing permutation and subtracting the canonical one.
BAIT = re.compile(r"[iI] have read the [dD][cC][oO] document and [iI] hereby sign the [dD][cC][oO]")

ROOT = Path(__file__).resolve().parent.parent
failures: list[str] = []


def check(label: str, ok: bool, detail: str = "") -> None:
    print(f"  [{'PASS' if ok else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
    if not ok:
        failures.append(label)


print("1. upstream regex sanity")
check(
    "canonical phrase matches upstream regex",
    bool(UPSTREAM_DCO_RE.match(CANONICAL)),
    repr(CANONICAL),
)
# Guards against someone "fixing" the canonical phrase to conventional casing.
check(
    "conventional casing is (still) rejected upstream",
    not UPSTREAM_DCO_RE.match(
        "I have read the DCO document and I hereby sign the DCO"
    ),
    "documents why this repo must keep the lowercase form",
)

print("2. workflow config")
wf = yaml.safe_load((ROOT / ".github/workflows/cla.yml").read_text(encoding="utf-8"))
job = wf["jobs"]["dco-check"]["steps"][0]["with"]
prompt = job["custom-notsigned-prcomment"]

quoted = re.search(r"^> (.+)$", prompt, re.M)
check("workflow YAML parses", True)
check("use-dco-flag is true", str(job["use-dco-flag"]).lower() == "true")
check(
    "document points at this fork",
    job["path-to-document"].startswith("https://github.com/nailoooog8/PokeClaw/"),
    job["path-to-document"],
)
if quoted is None:
    check("prompt quotes a signature line", False, "no '> ' line found")
else:
    phrase = quoted.group(1).strip()
    check("prompt quotes a signature line", True, repr(phrase))
    check("quoted phrase == canonical", phrase == CANONICAL)
    check("quoted phrase matches upstream regex", bool(UPSTREAM_DCO_RE.match(phrase)))

print("3. documentation")
for rel in ("CLA.md", "CONTRIBUTING.md"):
    body = (ROOT / rel).read_text(encoding="utf-8")
    check(f"{rel} contains canonical phrase", CANONICAL in body)
    variants = {m.group(0) for m in BAIT.finditer(body) if m.group(0) != CANONICAL}
    check(f"{rel} shows no mismatched-casing variant", not variants,
          "" if not variants else repr(sorted(variants)))

print()
if failures:
    print(f"FAILED: {len(failures)} check(s): {', '.join(failures)}")
    sys.exit(1)
print("OK: DCO signature phrase is consistent and matches the upstream checker.")
