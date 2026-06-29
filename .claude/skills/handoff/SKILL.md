---
name: handoff
description: Write a phase handoff document in claude-sessions/handoff-docs/ for a completed phase of the sales-forecasting-platform build, and wire it into the implementation plan. Captures what shipped, decisions, how-to-run, gotchas, git/PR state, the next phase, AND anything done outside the plan or repo. Use when a phase (or a meaningful chunk of work) is finished and you want a clean hand-off for the next session.
---

# Phase handoff document

Produce a single hand-off doc so whoever picks up the next phase has everything they need, then
update the implementation plan to reference it. Output lives under `claude-sessions/handoff-docs/`
(gitignored, private) — it may reference private/sensitive specifics freely and must **never** be committed.

## 1. Determine the phase
- Handoff file: `claude-sessions/handoff-docs/phase-<N>-handoff.md`, where `<N>` is the **completed** phase
  (e.g. `phase-0-handoff.md`). If the work isn't a numbered phase, name it for the work done
  (e.g. `setup-handoff.md`) and say so at the top.
- If the phase number is ambiguous, ask the user which phase this handoff is for.

## 2. Gather inputs (don't write from memory alone — verify)
- **All previous handoffs:** read every existing `claude-sessions/handoff-docs/phase-*.md` (in order).
  This is mandatory — the new handoff must build on them, not restate or contradict them.
- **All previous phase plans:** read prior `claude-sessions/implementation-plan/phase-*.md` and the index
  `README.md` so the through-line across phases is consistent.
- **The plan for this phase:** `claude-sessions/implementation-plan/phase-<N>-*.md`.
- **The driving docs:** `private/Build-Delivery-Plan-v3.md` and `private/Design-Doc-v3-Consolidated.md`.
- **The approved execution plan** (if any): `~/.claude/plans/*.md`.
- **Actual repo/git state:** `git log --oneline`, branch, `git status`, and PR state via `gh pr list` / `gh pr view`.
- **Verify build/run claims** before asserting them green (e.g. `mvn verify`, `make up`) — report real results.

### 2a. Reconcile against prior handoffs (ensure nothing was dropped)
Before writing, walk the **"Open items" and "Work done outside the plan"** sections of every previous
handoff. For each item, determine its current state and account for it explicitly:
- **Resolved** this phase → note it under "Carried forward" as done (with how/where).
- **Still open** → carry it forward into this handoff's "Open items" so it isn't lost.
- **Obsolete** → say so and why.
Also re-check earlier phases' **acceptance criteria** still hold (e.g. CI still green, `make run` still boots) —
a later phase can silently break an earlier guarantee; flag any regression.

## 3. Write the handoff using this structure
Mirror the Phase 0 handoff (`claude-sessions/handoff-docs/phase-0-handoff.md`) as the template. Sections:
- **Header** — status (✅ complete / ⚠️ partial), date, "private/gitignored" note.
- **TL;DR** — 2–4 sentences: the state someone inherits.
- **References** — links to the implementation-plan index + this phase's plan doc, the delivery plan,
  the design doc (v3), the approved execution plan, and `CLAUDE.md`.
- **What shipped** — concrete deliverables, file-grouped.
- **Locked decisions** — a table of decisions made this phase to carry forward (with values).
- **How to build / run / verify** — exact commands + expected results.
- **Gotchas / non-obvious** — traps, fixes applied, environment quirks.
- **Git / PR state** — branches, key commit SHAs, open/merged PRs with links.
- **Carried forward from prior phases** — for phase ≥1, REQUIRED: the result of §2a — which prior
  open items / outside-the-plan items were resolved this phase (and how), which are still open, and any
  earlier acceptance criteria re-verified or found regressed. For phase 0, state "N/A — first phase."
- **Next phase** — what's next and the first concrete step.
- **Open items / decisions pending** — a checklist that *includes* the still-open items carried forward
  from prior handoffs (don't let them disappear).
- **⭐ Work done outside the plan / repo** — REQUIRED. List anything not in this phase's plan:
  deviations from the approved plan, ad-hoc decisions, repo/infra/config actions (e.g. repo creation,
  CI, `.gitignore`, new skills, settings), tooling changes, hygiene fixes, and **off-repo / external
  work** (e.g. scheduling/logistics, accounts created, services configured). If nothing was done outside
  the plan, state that explicitly ("None — work stayed within the phase plan."). Never silently drop it.

## 4. Update the documents accordingly
- **`claude-sessions/implementation-plan/README.md`** — in the phase table, set this phase's **Handoff**
  cell to a link (`[phase-<N>-handoff](../handoff-docs/phase-<N>-handoff.md)`) and update its **Status**.
- **`claude-sessions/implementation-plan/phase-<N>-*.md`** — add/refresh a one-line link to the handoff
  in its outcome section.

## 5. Verify, don't commit
- Confirm the new file and the whole `claude-sessions/` tree are gitignored
  (`git check-ignore claude-sessions/handoff-docs/phase-<N>-handoff.md`) and untracked.
- These are private working docs — **do not** stage or commit them. Report the path(s) written/updated.
