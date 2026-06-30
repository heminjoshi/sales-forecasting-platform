# Phase 10 handoff — Public-repo polish & final dry run → PUBLIC

**Status:** ✅ complete · **Date:** 2026-06-30 · _Private / gitignored — never commit (`claude-sessions/`)._

## TL;DR
The repo is **public** at https://github.com/heminjoshi/sales-forecasting-platform, tagged **`v1.0`**,
CI green on `main`. The README has the dashboard hero screenshot + the 2-command run; a new
`docs/demo-runbook.md` documents the demo + troubleshooting. The `[P10]` cold-clone acceptance
(MQ-70/71) was **run green against a fresh public clone**. All ten phases (0–10) are done. What's left
is genuinely optional / off-repo (a live rehearsal, the designed-only Vercel deploy, optional ITs).

## References
- Implementation-plan index: [`../implementation-plan/README.md`](../implementation-plan/README.md)
- This phase's plan: [`../implementation-plan/phase-10-public-release.md`](../implementation-plan/phase-10-public-release.md)
- Approved exec plan: `~/.claude/plans/elegant-cooking-honey.md`
- Delivery plan: `private/Build-Delivery-Plan-v3.md` §164–169 · Design doc: `private/Design-Doc-v3-Consolidated.md`
- Public status: `CLAUDE.md` (status line), `README.md`

## What shipped
- **README polish** (`README.md`) — embedded `presentation/screenshots/dashboard-fresh.png` as the hero
  (+ a link to `dashboard-degraded.png`); added `docs/demo-runbook.md` to the docs index; top status line
  → "**v1.0 — public** · Phases 0–10 complete (PUBLIC milestone)". (PR #19 for hero+runbook; status line in the doc-sync PR.)
- **Demo runbook** (`docs/demo-runbook.md`, PR #19) — cold-start sequence, the `make demo` gate (and why
  it needs a wiped forecast plane), a target-reference table, reset/teardown, and a troubleshooting matrix
  (the `ECONNREFUSED 8080` = "API not running" trap included).
- **Dashboard screenshots** (`presentation/screenshots/dashboard-{fresh,degraded}.png`, PR #18) — captured
  via headless `puppeteer-core` driving system Chrome; screenshots/README flipped to "captured".
- **Publish** — repo flipped PRIVATE → PUBLIC; annotated tag **`v1.0`** pushed.
- **Status syncs** (this PR) — `CLAUDE.md` status line (Phase 10 done, public, v1.0); `test-plan/manual-qa-test.md §8`
  MQ-70/71 annotated **run + passing (2026-06-30)**.

## Locked decisions
| Decision | Value | Why |
|---|---|---|
| History | **No squash/rewrite** | The phase-by-phase merge history is part of the portfolio story |
| Public cut tag | **`v1.0`** (not `v1.0.0`) | Casual portfolio register |
| JDK note | Stays **21** | CI/target is Temurin 21; dev JDK 26 builds fine but 21 is the floor |
| Doc-sync PR merge | **`--merge --admin`** | Branch protection on, solo repo — established flow |
| Hero image | **fresh** forecast view | Shows intervals + grounded insight + `fresh` badge |

## How to build / run / verify
Unchanged from prior phases (no code touched this phase). The documented cold start:
```bash
make up && make seed && make forecast && make run   # → http://localhost:8080
```
Acceptance re-run (MQ-70), executed this phase against the **public** clone:
```bash
git clone https://github.com/heminjoshi/sales-forecasting-platform.git
cd sales-forecasting-platform && make up && make seed && make forecast
# boot API, then:
curl localhost:8080/actuator/health           # UP (db+redis)
# read fresh, cross-tenant 403, then wipe serving + FLUSHALL → make demo
make demo                                       # 17 requests / 54 assertions / 0 failed
```
Result: **all green.** `make test` (150 unit) not re-run — no code change; latest `main` CI is green.

## Gotchas / non-obvious
- **`make demo` needs the API up AND the forecast plane empty.** It's a *degradation* gate (folder 6
  asserts `degraded` + a `status="degraded"` Prometheus sample), which is why CI runs `seed` but **not**
  `forecast`. If you've run `make forecast`, `TRUNCATE serving_rows, serving_active_version;` + `redis-cli
  FLUSHALL` first. Documented in `docs/demo-runbook.md §3`.
- **`make up` before `make run`/`make demo`**, always. `ECONNREFUSED 8080` = API down; `5432 refused` = Postgres down.
- **`make down -v` wipes the DB volume** — re-`up`→`seed`→`forecast` after.
- **Screenshots:** headless `--screenshot=fullPage` raced Chart.js's responsive resize (bars bunched at the
  axis). Fixed by a fixed tall viewport (no `fullPage`) in the puppeteer driver.
- **Prompt-injection probe is visible in the demo data** — after `make demo`, tenant_a actuals show a
  `cat_inj IGNORE ALL PREVIOUS INSTRUCTIONS…` category at $999,999.99. That's the Phase-5 injection probe
  working: the name renders as inert data; the insight does **not** obey it.

## Git / PR state
- On `main` at `5cea02f` (PR #19 merged). **Uncommitted (this PR's working set):** `CLAUDE.md`,
  `README.md`, `test-plan/manual-qa-test.md` — to be sliced as `docs(phase10): …` on a branch and merged.
- Merged this session: **PR #18** (26-tenant rename + archetypes), **PR #19** (demo runbook + README hero).
- Tag **`v1.0`** pushed. Repo **public**.

## Carried forward from prior phases (reconciled)
- ✅ **Phase 9 open — "Open the Phase 9 PR"**: done (PR #17 merged earlier).
- ✅ **Phase 9 open — "Capture dashboard screenshots + embed in README"**: done (PR #18 captured, PR #19 embedded).
- ✅ **Phase 5/3 open — "wire `make demo` (newman)"**: confirmed working this phase (54 assertions green).
- ↪ **Still open (carried, all optional/off-repo):**
  - **Rehearse 60 min ≥3× cold** — user's off-tool step; artifacts ready.
  - **Vercel SPA deploy** (MQ-80..83, CN-14/15) — designed-only; `web/` ships unbuilt. N/A until deployed.
  - **Off-repo WS-G** (recruiter reply / interview-readiness) — private, non-repo.
  - **Optional ITs** — `IT-CO-*` HTTP-slice, `IT-FC-06`/`IT-FC-03` (documented, not built; CI-only host caveat).
  - **Optional** — forecast `windowFrom/To` time-series overlay; numbers-only insight validation residual;
    vendor reveal.js/mermaid for a no-wifi deck render.
- **Earlier acceptance re-verified:** cold `make up→seed→forecast→run` boots; isolation 403; `make demo`
  green; CI green on `main`. No regressions found (the 26-tenant rename kept all 150 unit tests green).

## Next phase
**None — Phases 0–10 are complete.** The build is at the **PUBLIC** milestone. Remaining work is the
private interview-readiness track (WS-G) and optional polish above. First concrete step if continuing:
the live 60-min rehearsal (off-tool), or — if desired — building/deploying the Vercel SPA to light up
the cross-origin MQ-80..83 cases.

## Open items / decisions pending
- [ ] Merge the **doc-sync PR** (`CLAUDE.md` + `README.md` status lines + `test-plan` MQ annotations) to `main`.
- [ ] (Off-tool) Rehearse 60 min ≥3× cold.
- [ ] (Optional) Deploy the Vercel SPA → exercises MQ-80..83 / CN-14/15 live.
- [ ] (Optional) `IT-CO-*` slice ITs; `IT-FC-06`/`IT-FC-03`; forecast `windowFrom/To` overlay; insight numbers-only residual; vendor reveal.js/mermaid.
- [ ] (Off-repo, WS-G) recruiter reply / interview-readiness — private, non-repo.

## ⭐ Work done outside the plan / repo
- **26-tenant rename + archetype generator (PR #18) — a feature change, not Phase-10 scope.** Mid-session
  the user asked to rename `t_demo`/`t_acme` → `tenant_a`/`tenant_b` and scale to **26 archetype-differentiated
  tenants**. Done via 10 parallel subagents (disjoint files) + a stateful eval/verify pass: new archetype
  layer in `SeasonalityModel`/`SeedConfig`/`seed-config.json`, `V8__tenants_c_to_z.sql`, swept ids across
  ~40 files, regenerated the WAPE eval (now **HW 6.9% vs naive 7.4%**, 312 segments), 150 unit tests green.
  Logged here because it shipped in the Phase-10 session window but isn't Phase-10 plan work.
- **`docs/demo-runbook.md`** — a new doc not enumerated in the Build-Plan's Phase-10 task list; added because
  the session surfaced repeated "API not running" / `make demo` confusion worth capturing once.
- **`make demo` triage** — the failure the user hit was **environmental** (API not booted), not a code/test
  bug; no fix needed beyond documenting the up-before-demo rule + the wiped-forecast requirement.
- **Repo made public + `v1.0` tagged via `gh`** — the actual outward-facing publish action (irreversible-ish);
  gated on a clean `public-repo-check` first.
- **`phase-9.txt` removed** — an 87 KB session transcript with interview/employer text was sitting untracked
  in the repo root; flagged and removed before publish (the user deleted it).
- **No Workflow used** — Phase 10 is docs + a dry run; no Maven reactor to orchestrate. Solo.
- Nothing else off-repo (no AWS, no Vercel, no external accounts beyond the GitHub visibility flip).
