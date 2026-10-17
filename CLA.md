# Developer Certificate of Origin (DCO), Version 1.1

**Project:** PokeClaw  
**Upstream:** [`agents-io/PokeClaw`](https://github.com/agents-io/PokeClaw) — Copyright 2026 agents.io  
**This fork:** [`nailoooog8/PokeClaw`](https://github.com/nailoooog8/PokeClaw)

Thank you for your interest in contributing to this fork of PokeClaw.

> **What changed and why.** The upstream project required contributors to **assign**
> their copyright to Nicole (agents.io). This fork does not. It is not the upstream
> project, is maintained independently, and cannot grant rights it does not hold —
> so routing contributors' copyright to a third party was both wrong and
> uncollectable. Under the Apache License 2.0 that already governs this repository
> (see [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE)), a CLA is unnecessary. The Linux
> kernel, Kubernetes, and most Apache-2.0 projects use this DCO instead.
>
> **You keep your copyright.** You only certify that the contribution is yours and
> that you have the right to submit it.

By submitting a pull request or otherwise contributing to this project, you accept and agree to the following terms for your present and future contributions.

---

## 1. Definitions

**"Contribution"** means any work of authorship, including modifications or additions to the project, that you submit to this project.

**"You"** means the individual or legal entity accepting this agreement.

**"Project"** means this repository (`nailoooog8/PokeClaw`) and any derivative of it.

---

## 2. Copyright — retained by you

**You retain all copyright and other rights in your Contributions.** Nothing in this
agreement assigns, transfers, or licenses your copyright away except the license
granted in section 3.

This is a deliberate departure from the upstream `CLA.md` (Nicole / agents.io), which
assigned copyright to the upstream owner. That upstream agreement does not apply to
this fork.

---

## 3. License grant

You grant the Project maintainers and recipients of software distributed by the
Project a perpetual, worldwide, non-exclusive, royalty-free, irrevocable **copyright
license** to reproduce, prepare derivative works of, publicly display, publicly
perform, sublicense, and distribute your Contributions and derivative works thereof.

This grant is identical to the Apache-2.0 license already covering the rest of the
repository, so it introduces no additional terms for your contribution.

---

## 4. Patent License

You grant the Project maintainers and recipients a perpetual, worldwide,
non-exclusive, royalty-free, irrevocable patent license to make, have made, use,
offer to sell, sell, import, and otherwise transfer your Contribution, where such
license applies only to patent claims licensable by you that are necessarily
infringed by your Contribution.

---

## 5. Right to Grant

You confirm that:

- You are legally entitled to grant the above licenses.
- If your employer has rights to intellectual property you create, you have received permission to make this Contribution on behalf of your employer, or your employer has waived such rights.
- Each Contribution is your original creation, or you have the right to submit it under these terms.
- **Each Contribution is submitted under the Apache License 2.0**, the license of this repository. Do not submit code you do not hold the right to license this way.

---

## 6. Upstream attribution is unaffected

This Project is a fork of `agents-io/PokeClaw`, Copyright 2026 agents.io. As required
by Apache-2.0 §4, the upstream attribution in `LICENSE`, `NOTICE`, and the README is
preserved and **must not** be removed, obscured, or misrepresented. Your contribution
does not license, alter, or waive any upstream copyright, and does not imply any
endorsement by the upstream author or `agents.io`.

This fork is an independent distribution. It is not affiliated with, endorsed by, or
a substitute for the upstream project.

---

## 7. No Warranty

You provide your Contributions on an "AS IS" basis, without warranties or conditions of any kind.

---

## 8. Signing

To accept these terms, comment on your pull request with exactly this line,
**entirely lowercase**:

```
i have read the dco document and i hereby sign the dco
```

> **Why all lowercase?** The signature checker
> (`contributor-assistant/github-action` v2.6.1,
> `src/pullrequest/signatureComment.ts:67`) matches this pattern with no
> case-insensitive flag, and hard-codes both the leading `i` and the word `dco` in
> lowercase. The conventional `I have read the DCO document and I hereby sign the
> DCO` is **silently rejected**. This is an upstream defect we cannot fix without
> forking the action's bundled `dist/`, so this document quotes the exact form the
> checker accepts. Verified by replaying the upstream regex against both casings —
> only the all-lowercase form matches.
> See `scripts/check_dco_consistency.py`, which enforces this.

The CI check requires this comment. Signatures are recorded in
`signatures/cla.json` so they can be re-verified when that file is versioned.

Commit trailers (`Signed-off-by:`) are **not** what this check reads. If you prefer
sign-off-by-trailer locally, `git commit -s` is still fine — it is simply not
verified by CI here.
