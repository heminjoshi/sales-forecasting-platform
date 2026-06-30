# Phase 10 — Public-repo polish & final dry run  `[all]` → PUBLIC

> Approved exec plan (driven via `/implement-phase 10`). A **docs + dry-run** phase — no Java/Maven,
> so **no Workflow reactor** (parallel build agents would have nothing to build). Most of it shipped
> in-session ahead of this doc; this records the close-out.

## Objective & acceptance

Make the repo a clone-and-run public portfolio artifact.
**Acceptance (verbatim, Build-Plan §169):** "a stranger clones, runs in 2 commands, sees the
dashboard, reads the design + ADRs." Closes the **PUBLIC** milestone.

Deliverables (Build-Plan §165–167):
- README: pitch, architecture diagram, **2-command run**, dashboard screenshots, design-doc links,
  tech stack, "built vs designed" note.
- Secret scan; confirm `.env`/creds/`claude-sessions/` gitignored; no employer references.
- Tidy history; tag **`v1.0`**; CI badges green; final dry run on a clean machine (clone → run → demo).

## Current state (audited)

Shipped this session **before** this doc was written:
- README hero screenshot embedded (`presentation/screenshots/dashboard-fresh.png`) + degraded link;
  `docs/demo-runbook.md` added and linked (PR #19, merge `5cea02f`).
- Hygiene gate (`public-repo-check`) run — clean (no secrets, no tracked `private/`/`claude-sessions/`,
  no employer name; the only scan hits were policy text + the scanner's own regex).
- Repo flipped **PRIVATE → PUBLIC**; **`v1.0`** annotated tag pushed; CI green on `main` (ci/docker/infra).
- `phase-9.txt` (an interview-laden session transcript) removed from the working tree.
- `[P10]` test spec already existed (`test-plan/manual-qa-test.md §8`: MQ-70 cold-clone, MQ-71 hygiene).

Gap this doc closes: **MQ-70 never run against the public repo**, and the close-out (status-line syncs,
plan doc, handoff) wasn't done.

## Decisions locked

- **No history rewrite / squash.** The phase-by-phase merge-commit history is part of the portfolio
  story (shows the WS-A…WS-G workstream cadence); keep it.
- **JDK note stays "21".** README/CI target Temurin 21; the dev machine runs JDK 26 (builds fine). Not
  worth a doc change — 21 is the supported floor.
- **`v1.0` is the public cut**, not `v1.0.0` — matches the casual portfolio register.
- **Merge-with-admin** for the doc-sync PR (branch protection on, solo repo) — repo's established flow.

## Steps

1. **MQ-70 cold-clone acceptance** — `git clone https://github.com/heminjoshi/sales-forecasting-platform`
   (public, no auth) → `make up` → `make seed` → `make forecast` → boot → verify health UP, dashboard
   200, forecast read `fresh`, cross-tenant 403, and `make demo` (after serving wipe → degraded) green.
   *Done — all green; tore the clone stack down.*
2. **MQ-71 / `public-repo-check`** — re-confirm clean. *Done.*
3. **Sync tracked status docs** — `CLAUDE.md` status line (Phase 10 done, public, `v1.0`); `README.md`
   top status line (`v1.0 — public`, Phases 0–10); `test-plan/manual-qa-test.md §8` MQ-70/71 annotated
   run + passing (2026-06-30).
4. **Close-out docs (gitignored)** — this doc + flip the index README row to ✅; `handoff` skill →
   `phase-10-handoff.md`.
5. **Commit + PR** — `docs(phase10): …` branch; `public-repo-check`; push; PR → `main`; merge admin.

> **Outcome:** ✅ done — see [`../handoff-docs/phase-10-handoff.md`](../handoff-docs/phase-10-handoff.md).

## Acceptance checklist

- [x] Public clone comes up in the documented commands; dashboard renders; ADRs/design readable (MQ-70).
- [x] Hygiene gate clean (MQ-71 / `public-repo-check`).
- [x] Repo public; `v1.0` tagged; CI green on `main`.
- [x] README hero screenshot + 2-command run + doc links present.
- [ ] Status-doc syncs merged to `main` (this PR).

## Out of scope / deferred

- Vercel SPA deploy + S3/CloudFront (designed-only; `web/` ships unbuilt).
- Any CDK deploy (synth + assertions only).
- Probe-bank / FAQ regeneration (explicitly deferred in the plan — rebuild after completion).
- The interview-readiness track (WS-G, private/non-repo).
