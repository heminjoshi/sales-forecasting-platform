# Phase 9 — Presentation & rehearsal  `[WS-F]` → 🎤 PRESENTATION-READY

> Approved exec plan (driven via `/implement-phase 9`). A **docs/deck + rehearsal** phase — no Maven,
> no Workflow reactor (authoring needs one coherent voice). Delivered as one PR
> (`feat/phase9-presentation`), sliced `feat(phase9)`/`docs(phase9)`.

## Objective & acceptance

Produce the interview presentation artifacts and a verified cold-start live demo.
**Acceptance (verbatim, Build-Plan §160):** "full run-through to time; demo (incl. dashboard) runs
clean from cold." Closes the **🎤 PRESENTATION-READY** milestone.

Deliverables (Build-Plan §155–158):
- `presentation/deck` to the exact slide budget: Problem+Assumptions (2) · Requirements (5) · HLD (10)
  · **Component Deep-Dive (20)** · AI Integration (10) · Scale & Perf (5) · Q&A (8).
- **Architectural Choices & Trade-offs** slides — the §5 A/B/C comparison + the 10 ADRs, rehearsing the
  *rejected-alternative* and *what-I-didn't-build* beats (Build-Plan §156, "highest-scored").
- `presentation/intro-achievements.md` (Intro 5 + Achievements 10, 2 quantified stories + an HLD
  diagram); `demo-script.md` (live sequence incl. UI + degradation); `speaker-notes.md`.
- Rehearse 60 min ≥3× cold from `docker-compose up` (the user's off-tool step).

## Current state (audited — 2 parallel Explore agents)

- `presentation/` holds only `.gitkeep`; no Phase-9 plan doc existed.
- **`docs/hld.md` ≡ `private/Design-Doc-v3-Consolidated.md`** (verbatim, 386 lines) — every deck fact is
  sourceable from a **public** file, so the deck stays generic/public-safe.
- All raw material present: hld.md (§5 A/B/C table @74–81, NFR priority @55–67, 4 data shapes @231–252,
  capacity math @385, local↔cloud swap @357–366), the 10 ADRs (`docs/adr/0001–0010`, comparative
  chosen/rejected/"didn't-build" format), 5 mermaid diagrams (`docs/diagrams/*`), and the WAPE eval
  (`docs/forecast-eval-report.md`: HoltWinters pooled **WAPE 0.0686** vs SeasonalNaive 0.0755, 24
  segments / 2016 points).
- Cold-start sequence verified: `make up → seed → run → forecast → open :8080` (+ optional `make
  trickle`). Degradation is **manual** (`TRUNCATE serving_rows, serving_active_version;` +
  `redis-cli INCR tenantver:t_demo` → forecast read still **200** `degraded`/`pending`); documented in
  Postman folder + runbook; `ForecastDegradationIT` mirrors it.
- **Live-demo friction flagged:** the dashboard's only network dependency is the Chart.js **CDN** in
  `static/index.html` (breaks offline); README "## Architecture" is still "_to be added_"; no screenshots.

## Decisions locked

- **Deck = all three renderings from one source** (user). Canonical content authored once as a
  Marp-compatible `presentation/deck/deck.md` (`---` separators) — which *also* IS the GitHub-markdown
  deck (renders inline incl. mermaid). A thin reveal.js `presentation/deck/index.html` loads that same
  `deck.md` via `data-markdown` → offline clickable slides. One content file, three renderings.
- **Demo polish = vendor Chart.js offline + capture screenshots** (user). **No `make degrade` target**
  (declined) — the degradation steps live in `demo-script.md`.
- **Solo, no Workflow.** Bulky Component-Deep-Dive (20) + Q&A (8) may be drafted by parallel sub-agents
  against the extracted content contract, then stitched + line-edited for one voice.
- **Public-repo hygiene:** all of `presentation/` is **generic** — no employer/recruiter/STAR framing.
  The private interview narrative stays in gitignored `interview-prep/`.

## Steps

1. **Plan doc + index** (this file) + flip implementation-plan README row 9 → this doc.
2. **`[P9]` test cases** in `test-plan/manual-qa-test.md` (new §10 Presentation & rehearsal: full
   run-through to time, cold-start clean, the degradation beat, the now-**offline** demo) + update
   MQ-60/63 for the vendored Chart.js + `[P9]` phase-gating bullet in `test-plan/README.md`.
3. **Deck content** — `presentation/deck/deck.md` to the budget; trade-offs slides paste the §5 table
   verbatim + one slide per ADR; achievements cite the real numbers.
4. **reveal.js wrapper** — `presentation/deck/index.html` (reveal + markdown + mermaid plugins via CDN,
   note to vendor for fully-offline).
5. **Companions** — `intro-achievements.md`, `demo-script.md`, `speaker-notes.md`, `presentation/README.md`.
6. **Vendor Chart.js** — curl `chart.umd.min.js` into
   `service/topsales-api/src/main/resources/static/vendor/`; switch `index.html`'s `<script src>` CDN →
   local path.
7. **Screenshots** — run cold-start (Docker), capture `fresh` + `degraded` dashboard →
   `presentation/screenshots/`; else document capture steps + placeholder.
8. **Tracked-doc sync** — `CLAUDE.md` status → "Built through Phase 9"; `README.md` Architecture section
   gets the mermaid + a screenshot.
9. **Verify** — `make test` green; cold-start live demo + degradation beat; render the deck.
10. **Close out** — `handoff` skill; `public-repo-check`; PR to `main`.

## Acceptance checklist

- [ ] `presentation/deck/deck.md` covers all 7 budgeted sections (~58 slides) + the trade-offs slides.
- [ ] `presentation/deck/index.html` renders the deck as clickable slides offline-capable.
- [ ] `intro-achievements.md` / `demo-script.md` / `speaker-notes.md` / `presentation/README.md` present, generic.
- [ ] Chart.js vendored; dashboard renders with **no internet**.
- [ ] Screenshots captured (or capture steps documented + placeholder).
- [ ] `[P9]` cases in `test-plan/`; README phase-gating updated.
- [ ] `make test` green; cold-start demo + degradation beat verified live (or documented as Docker-gated).
- [ ] `CLAUDE.md`/`README.md` synced; `public-repo-check` clean.

## Outcome

✅ **Done** — see the handoff: [`../handoff-docs/phase-9-handoff.md`](../handoff-docs/phase-9-handoff.md).
Committed on `feat/phase9-presentation` (3 slices); `make test` green; the cold-start demo + degradation
beat verified live (fresh→degraded→fresh). PR not yet opened.

## Out of scope / deferred

- Private interview narrative (why-employer, STAR, recruiter) → gitignored `interview-prep/`, never tracked.
- `make degrade` helper (user declined).
- README hero polish, `v1.0` tag, secret-scan-and-publish → **Phase 10**.
- Real Vercel SPA deploy → still open (external).
- The actual 60-min rehearsal ≥3× → the user's off-tool step; this phase ships the enabling artifacts.
