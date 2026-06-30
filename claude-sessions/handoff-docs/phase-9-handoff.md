# Phase 9 handoff — Presentation & rehearsal 🎤 `[WS-F]`

**Status:** ✅ complete (committed on `feat/phase9-presentation`; PR not yet opened) · **Date:** 2026-06-29 ·
_Private / gitignored — never commit._

## TL;DR
Phase 9 closes the **🎤 PRESENTATION-READY** milestone. It's a **docs/deck phase** — the only
production-code touch is vendoring Chart.js so the demo dashboard is fully offline. Shipped: the
interview **deck** (`presentation/deck/deck.md`, ~60 slides to the Build-Plan budget, sourced entirely
from the **public** `docs/hld.md` ≡ design doc + the 10 ADRs, so it's generic/public-safe) rendered three
ways (Marp / GitHub-inline / a reveal.js `index.html` that loads the same `deck.md`), plus
`intro-achievements.md`, `demo-script.md`, `speaker-notes.md`, `presentation/README.md`. The cold-start
live demo + the degradation beat were **verified live** end-to-end (real fresh→degraded→fresh). The actual
60-min rehearsal ≥3× is the user's off-tool step; this phase ships the artifacts that enable it.

## References
- Implementation-plan index: `claude-sessions/implementation-plan/README.md`
- This phase's plan doc: `claude-sessions/implementation-plan/phase-9-presentation.md`
- Delivery plan: `private/Build-Delivery-Plan-v3.md` (Phase 9, §154–160, `[WS-F]`)
- Design doc / deck source: `docs/hld.md` (≡ `private/Design-Doc-v3-Consolidated.md`), `docs/adr/0001–0010`, `docs/diagrams/*`, `docs/forecast-eval-report.md`
- Approved exec plan: `~/.claude/plans/hazy-spinning-quilt.md`
- `CLAUDE.md` (status line bumped to "Built through Phase 9")

## What shipped

### Presentation artifacts (`presentation/`, committed — all generic/public-safe)
- **`presentation/deck/deck.md`** — the canonical deck, **60 slides** (1 title + 59 `##`), Marp frontmatter
  + `---` separators. Sections map 1:1 to the Build-Plan §155 budget (Problem+Assumptions · Requirements ·
  HLD · **Component Deep-Dive 20** · AI Integration · Scale&Perf · Q&A). The **§5 A/B/C fork table** is
  pasted verbatim and each of the **10 ADRs is one slide** (decision · chosen · rejected · "didn't build") —
  the highest-scored trade-offs material (Build-Plan §156). 3 mermaid diagrams (architecture, degradation
  chain, insight resilience). Achievements cite real numbers (WAPE 0.0686 vs 0.0755, 58/58 + 4 ITs, p99 SLO).
- **`presentation/deck/index.html`** — reveal.js wrapper (reveal 5.1 + markdown/highlight/notes plugins +
  mermaid 10.9, via jsDelivr CDN). Fetches `deck.md`, strips the Marp frontmatter, renders mermaid code
  blocks after each slide. `S` = speaker view; `?print-pdf` = export. **One source, three renderings.**
- **`presentation/intro-achievements.md`** — opener (5 min) + the **2 quantified stories**: (1) an *earned*
  forecast (WAPE 6.9% HW vs 7.6% naive, and the naive doubles as the degradation floor); (2) multi-tenant
  isolation (403/404, RFC-7807) + the never-fail-closed degradation chain.
- **`presentation/demo-script.md`** — the cold-start sequence + the **degradation beat** (manual
  `TRUNCATE serving_rows, serving_active_version` + `redis-cli INCR tenantver:t_demo`), with timings and a
  failure-recovery cheatsheet. (No `make degrade` target — user declined; the two lines *are* the demo.)
- **`presentation/speaker-notes.md`** — per-section beats, the rejected-alternative/"didn't-build" framing,
  anticipated Q&A, a 60-min timing table, and a public-repo reminder.
- **`presentation/README.md`** + **`presentation/screenshots/README.md`** — how to render each format; how to
  capture the fresh + degraded dashboard shots (placeholder — capture during rehearsal; Phase 10 reuses).

### Demo polish (the one production-code touch)
- **Vendored Chart.js** — `service/topsales-api/src/main/resources/static/vendor/chart.umd.min.js`
  (pinned 4.4.3, 205 KB); `index.html` `<script src>` repointed CDN → `vendor/`. The dashboard now renders
  with **zero network dependency** (verified: served 200, no `jsdelivr` ref left in the page).

### Docs + test-plan sync (committed)
- **`CLAUDE.md` / `README.md`** — status → "Built through Phase 9 / PRESENTATION-READY"; the README
  Architecture section (was "_to be added_") now has the four-tier mermaid + a `presentation/` pointer +
  a screenshot placeholder; "next" → Phase 10.
- **`test-plan/manual-qa-test.md`** — new **§10 Presentation & rehearsal `[P9]`** (`MQ-90..96`: cold-start
  run-through, dashboard walk, the live degradation beat, recovery, fully-offline rehearsal, deck render,
  run-to-time); `MQ-60`/`MQ-63` updated for the vendored Chart.js. **`test-plan/README.md`** — `[P9]`
  phase-gating bullet.

## Locked decisions
| Decision | Value |
|---|---|
| Deck format | **All three from one source** — `deck.md` (Marp + GitHub) + a reveal.js `index.html` loading it |
| Demo polish | **Vendor Chart.js + capture screenshots**; **no** `make degrade` target (manual steps in the script) |
| Deck content source | **Public** `docs/hld.md`/ADRs/diagrams only → generic, public-safe by construction |
| Orchestration | **Solo, no Workflow** (authoring needs one voice; no Maven races in a docs phase) |
| Private interview narrative | Stays out of the repo (gitignored `interview-prep/`); `presentation/` is generic |

## How to build / run / verify
```bash
make test                       # full reactor unit gate → BUILD SUCCESS (verified; api 58/58)
# Render the deck (any one):
open presentation/deck/index.html          # reveal.js (or serve the dir: python3 -m http.server)
#   or open presentation/deck/deck.md on GitHub / in the Marp VS Code extension
# Live demo (verified this phase):
make up && make seed && make run            # (run is long-running) Postgres+Redis, seed, boot :8080
make forecast                               # write versioned serving rows → fresh
#   degradation beat (demo-script.md §3): TRUNCATE serving_rows,serving_active_version + INCR tenantver:t_demo
#   → forecast read still 200 'degraded'; make forecast again → 'fresh'
```
**Verified live this phase (real output):** cold-start clean; `mode=forecast` `fresh` (HIGH conf) →
wipe+bump → **HTTP 200 `degraded`** (LOW conf, 5 items, never 5xx) → `make forecast` → `fresh`. Plus:
vendored `/vendor/chart.umd.min.js` served 200 with **0** jsdelivr refs in the page; cross-tenant **403**,
unknown tenant **404**; `topsales_read_total{status="degraded"}` counter present and climbing.

## Gotchas / non-obvious
- **`make down` keeps the Postgres named volume** (`docker compose down`, no `-v`) — so serving rows from a
  prior `make forecast` persist across an "up/down" cycle. A `mode=forecast` read can show `fresh` before
  you run the batch this session. For a truly cold demo use `docker compose down -v` (or just run the batch).
- **The reveal.js wrapper needs `deck.md` fetchable** — most browsers allow the `file://` fetch, but some
  block it; if the deck is blank, serve the dir (`python3 -m http.server` in `presentation/deck/`).
- **Marp does not render mermaid inline** — those 3 slides show the diagram source under Marp; present via
  GitHub/reveal, or reference `docs/diagrams/*` / the screenshots. GitHub + reveal both render the mermaid.
- **Security hook flagged SRI** on the reveal/mermaid CDN `<script>`s. Left as-is for a local interview
  artifact; the documented **vendoring path** (OFFLINE NOTE in `index.html`) is the stronger mitigation —
  it removes the CDN entirely. Address in Phase 10 polish if the deck is ever hosted.
- **Screenshots not captured** — they need a live browser render; documented in `screenshots/README.md`
  with exact framing. Capture during the first rehearsal; the README Architecture hero embed is a placeholder.

## Git / PR state
- Branch: **`feat/phase9-presentation`** (off `main` @ `55f05e7`, which now includes the merged Phase 8 PR #16).
- Commits (3 slices): `f0525ae` feat vendor Chart.js · `89f4cde` docs presentation artifacts ·
  `d6db42f` docs status/README/`[P9]` test cases. Working tree clean.
- **PR not yet opened.** `make test` green locally; live demo verified. Open the PR to `main` when ready
  (`gh pr create`); CI should be green (no production logic changed — static resource + docs only).
- `public-repo-check` run this phase → **GO** (no secrets, no employer/recruiter names, `presentation/`
  generic; the only `intuit` hits are "int**uit**ive" + the generic word "interview").

## Carried forward from prior phases (§2a reconciliation)
- **Live-demo Chart.js CDN dependency (flagged in P8 `MQ-63`)** → **RESOLVED** ✅ this phase (vendored; demo
  fully offline; `MQ-63` rewritten as a fully-offline check).
- **README "## Architecture — to be added" (long-standing)** → **RESOLVED** ✅ (four-tier mermaid + pointers).
- **Testcontainers `*IT`s CI-only on this host** → **still open** (environment constraint). Note: the
  *compose* path (`make up`/`docker compose`) works fine on this host — only the bundled docker-java client
  used by Testcontainers is the issue. The live demo this phase ran cleanly against `make up`.
- **Vercel SPA real deploy (P7)** → **still open** (external; exercises CN-14/15, MQ-80..83).
- **Off-repo recruiter reply (WS-G)** → **still open** (external, non-code).
- **`IT-CO-*` CORS slice ITs · `IT-FC-06`/`IT-FC-03` documented-not-built · forecast `windowFrom/To` overlay
  + numbers-only insight residual** → **still open** (optional/deferred). The injection residual is now
  *documented in the deck* (AI Integration "prompt-injection" slide owns it as a residual).
- **README hero screenshots** → **still open** (deferred to capture-during-rehearsal / Phase 10).
- **Earlier acceptance re-verified:** `make test` still green (58/58 api, full reactor); the live stack
  boots, seeds, forecasts, degrades, and recovers — **no P0–P8 guarantee regressed** (only a static script
  src changed in production code).

## Next phase
**Phase 10 — Public-repo polish & final dry run (S–M) `[all]`.** First concrete step: capture the two
dashboard screenshots (`presentation/screenshots/README.md`), embed `dashboard-fresh.png` in the README
Architecture hero, then the README pitch/2-command-run/gif pass, a final secret scan + `/public-repo-check`,
tidy history, tag `v1.0`, and a clean-machine clone→run→demo dry run. **Acceptance:** a stranger clones,
runs in 2 commands, sees the dashboard, reads the design + ADRs.

## Open items / decisions pending
- [ ] **Open the Phase 9 PR** to `main` (`gh pr create` from `feat/phase9-presentation`); confirm CI green.
- [ ] **Capture dashboard screenshots** (fresh + degraded) → `presentation/screenshots/` and embed in README.
- [ ] **Rehearse 60 min ≥3× cold** (Build-Plan §158) — user's off-tool step; artifacts are ready.
- [ ] Optional: vendor reveal.js/mermaid into `presentation/deck/vendor/` for a no-wifi deck render (+SRI).
- [ ] Still open from prior phases: Vercel SPA deploy · off-repo WS-G recruiter reply · `IT-CO-*` slice ITs ·
      `IT-FC-06`/`IT-FC-03` (documented, not built) · forecast `windowFrom/To` overlay + insight residual.

## ⭐ Work done outside the plan / repo
- **Live cold-start demo actually executed** (Docker is up on this host) rather than just documented — booted
  the full stack, seeded, forecast, ran the degradation beat, and confirmed fresh→degraded→fresh + isolation
  403/404 + the degraded counter, then tore down. The plan allowed a documented-only fallback; the live run
  is stronger evidence for the acceptance gate.
- **README Architecture diagram** added (was a standing "to be added" gap, technically Phase-10 polish) —
  folded in here because the deck needed the same mermaid and it removes a public-repo rough edge now.
- **No Workflow used** (the plan's optional fan-out for the Deep-Dive/Q&A slides) — wrote the deck solo for
  one coherent voice; the content contract from the audit agents was enough.
- Nothing off-repo (no AWS, no Vercel, no external accounts, no PR opened yet).
