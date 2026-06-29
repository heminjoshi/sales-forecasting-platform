---
name: implement-phase
description: Plan and build the next phase of the sales-forecasting-platform end-to-end — orient on the private source-of-truth docs, audit current repo/branch state with parallel sub-agents, produce an approved plan, then orchestrate the implementation with a deterministic multi-agent Workflow (foundation → parallel work-streams → verify), and close out with a handoff. Use when the user wants to "build the next phase", "implement phase N", "do the next chunk", or otherwise drive a phase of the build forward. Completes the triad phase-status (where are we) → implement-phase (build it) → handoff (close it out).
---

# Implement a phase

Drive one phase (or a meaningful chunk) of the build from plan to verified code. This skill is the
codified, reusable version of the implementation track: **orient → audit → plan → orchestrate →
verify → hand off.** It is phase-agnostic — the target phase is the argument (`/implement-phase 5`);
if no phase is given, run `phase-status` first to determine the next unmet phase, then confirm it
with the user.

Respect `CLAUDE.md` and `CLAUDE.local.md`: tie work to the phase's exit criteria, don't add depth
before the vertical slice runs, explain non-trivial Java/Spring/CDK tradeoffs (the user is learning
the stack), and keep this **public** repo clean (no employer/interview framing in tracked files).

## 1. Orient (read the source of truth — don't work from memory)

Read, for the target phase:
- **`private/Build-Delivery-Plan-v3.md`** — scope, the phase's per-task `[ ]` checkboxes,
  workstreams WS-A…WS-G, exit/acceptance criteria, built-vs-designed.
- **`private/Design-Doc-v3-Consolidated.md`** — architecture, interface seams, the decision records
  (DR-1…DR-9), the per-component deep-dive, and the canonical data shapes
  (`SaleEvent → AggregateRow → ForecastRow/serving row → TopKResponse`).
- **`docs/lld.md`** (tracked, public) — the section(s) for this phase (e.g. §5 read pipeline, §7
  cache, §9 insight, §13 UI) and **`docs/adr/`**.
- **Every prior `claude-sessions/handoff-docs/phase-*.md`** in order — the new work must build on
  them, not restate or contradict. Pay attention to "Open items", "Carried forward", and "Work done
  outside the plan". Also read any `claude-sessions/implementation-plan/phase-*.md`.
- **`test-plan/`** (tracked, public) — the `README.md` (phase-gating list + conventions) and the
  relevant plan files (`integration-tests.md`, `load-tests.md`, `stress-tests.md`, `canary-tests.md`,
  `manual-qa-test.md`). The `[P<N>]`-tagged cases are the **test spec** the phase must satisfy; read
  the prior phases' cases so the new ones extend rather than duplicate them.

## 2. Audit current state (parallel, read-only sub-agents)

Don't trust status markers — verify against the repo. Launch **2 read-only sub-agents in parallel**
(single message, `Explore` or `general-purpose`):

- **Agent A — phase contract:** extract from the private docs the full scope, exit criteria, the
  interfaces/seams touched, the relevant DRs, and the canonical data shapes for this phase. Quote the
  contract text. Returns a structured report; writes nothing.
- **Agent B — repo/branch state:** `git status`/`git diff --stat`/`git log --oneline`; which
  `topsales-*` modules + files for this phase already exist; do they compile (`mvn -o compile`,
  fast); what's tested vs untested; what's committed vs uncommitted; what's missing against the exit
  criteria. (Note: `*IT` Testcontainers tests are **CI-only on this dev host** — `make test` is the
  local gate.) Returns a precise report; modifies nothing.

Wait for both. The audit often reveals the phase is partly built already (e.g. a scaffolded module,
or uncommitted work on a feature branch) — fold that into the plan instead of rebuilding it.

## 3. Plan + approval (in the main loop, not a workflow)

`EnterPlanMode`. Write the plan to the plan file with: a **Context** section (why, what the audit
found), the recommended approach only, the **critical files** (reuse existing seams — name them with
paths; don't rebuild `InsightGenerator`, `ForecastProvider`, `CacheShell`, repositories, etc.), and a
**Verification** section (`make test`, the phase's live acceptance checks). Use `AskUserQuestion` for
genuine scoping forks. Get sign-off with `ExitPlanMode`. Keep it scannable.

**Then persist the approved plan as a durable phase doc** (the ephemeral `~/.claude/plans/*` file is
not tracked and disappears): write `claude-sessions/implementation-plan/phase-<N>-<slug>.md` following
the shape in that dir's `README.md` (Objective & acceptance · Current state · Decisions to lock ·
Steps · Acceptance checklist · Out of scope), and flip the phase's row in the index
(`claude-sessions/implementation-plan/README.md`) from `_todo_` to the new doc link. Do this **before**
orchestrating — the doc is the build's working reference and the handoff links to it. (Phase 5 skipped
this and left a `_(approved exec plan)_` gap — don't repeat it.)

**Also author the phase's test cases in `test-plan/` (tracked, public).** The plan *is* the spec the
phase must satisfy, so write the cases as you plan — don't defer them to close-out. For each test type
the phase touches, add `[P<N>]`-tagged cases to the right plan file (`integration-tests.md`,
`load-tests.md`, `stress-tests.md`, `canary-tests.md`, `manual-qa-test.md`) covering happy paths **and**
the edge/corner/error cases, and where an error is the correct outcome document the exact status + RFC
7807 problem (follow that dir's README conventions and the existing case-ID/table style, e.g.
`IT-XX-NN`). Add the phase to the README's **phase-gating** list. Keep these **generic** — `test-plan/`
is committed to the public repo, unlike the gitignored `claude-sessions/` docs, so no employer/interview
framing. A case for behavior not yet built is written now and marked `@Disabled`/skipped until it lands.

## 4. Orchestrate the implementation (deterministic Workflow)

**Only when the user has opted into multi-agent orchestration** (the `Workflow` tool's rule — they
said "use a workflow"/"ultracode", invoked this skill expecting it, or confirmed it). Otherwise
implement solo, spawning sub-agents only for isolated parallel chunks, and note the tradeoff.

This repo is a **Maven multi-module reactor** — parallel `mvn` invocations race on `target/`. The
canonical Workflow shape (the Phase 3/4 "waves" pattern, made deterministic) is **three stages**:

- **Stage 1 — Foundation (one agent, sequential).** Touch only the *shared / contended* files:
  root + module `pom.xml`, `topsales-common` interfaces/records, `TopsalesProperties`,
  `application.yml`, `*Wiring` / `@Configuration`. Doing these first unblocks the fan-out and
  prevents merge conflicts. This agent may compile.
- **Stage 2 — Parallel work-streams (`parallel`/`pipeline`, partitioned by file ownership).** Each
  agent owns a **disjoint package/file set** and **must not run Maven** (hard rule in the prompt —
  parallel builds corrupt `target/`). Partition by ownership the way Phase 4 did (e.g. A: service +
  degradation, B: cache + batch, C: dashboard + Postman). State the owned paths explicitly per agent.
- **Stage 3 — Verify (one agent).** A single `mvn -o test-compile` + `make test`, then an
  **adversarial review** pass (correctness + the phase's security talking point, e.g. injection
  grounding for Phase 5). One Maven invocation = no races.

If disjoint ownership can't be guaranteed, use `isolation: 'worktree'` per parallel agent instead
(more expensive). Author the `meta` block with one entry per `phase()` and matching titles. Read each
stage's result before the next; surface anything the agents couldn't finish.

## 5. Verify (report real results, never assert green untested)

- `make test` — the full-reactor unit gate (no Docker). Must be green.
- `make up && make seed && make run` (+ phase-specific targets like `make forecast`) and walk the
  phase's **acceptance criteria** live — e.g. for Phase 4, wipe the serving table → dashboard still
  renders `degraded`. Capture actual command output. These live checks should map back to the
  `manual-qa-test.md` / `canary-tests.md` cases you authored in §3 — run them, don't just assert them.
- `*IT`s are CI-only on this host — write them correct-for-CI, don't gate on local execution. The
  integration tests you added to `test-plan/integration-tests.md` are the spec for these `*IT` classes.

## 6. Close out

- Invoke the **`handoff`** skill to write `claude-sessions/handoff-docs/phase-<N>-handoff.md` and
  wire the implementation-plan index (set the phase row's **Status** to ✅ done and confirm its
  **Plan doc** column points at the `phase-<N>-*.md` you persisted in §3, not `_todo_`). (Handoff and
  plan docs both live under `claude-sessions/` — private/gitignored, never committed.)
- Commit in slices with the repo's `feat(phase<N>): …` convention; sync tracked docs (`CLAUDE.md`
  status line, `README.md` built-vs-designed, `docs/lld.md`, `docs/api/openapi.yaml`, and the
  `test-plan/` files — reconcile the cases you authored in §3 against what actually shipped, flipping
  any that were `@Disabled`-pending to live where the phase now satisfies them).
- Run **`public-repo-check`** before any push; open the PR to `main`.

## Output

A short report: what shipped (file-grouped), real `make test` / live-verify results, the git/PR
state, and the next phase's first concrete step.
