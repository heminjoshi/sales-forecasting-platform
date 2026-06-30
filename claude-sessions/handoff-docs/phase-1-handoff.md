# Phase 1 — Handoff

> **Status:** ✅ Complete (content delivered; Stage-3 PR #4 open for review). Hand-off for whoever picks up **Phase 2**.
> **Date:** 2026-06-28 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
Phase 1 (Design Artifacts) is done. The public repo now has a full, generic design suite — HLD,
component deep-dive, an implementation-grade **LLD**, a validated **OpenAPI 3.1** contract, **9
comparative ADRs**, 5 Mermaid diagrams, and a runbook. Delivered as three staged PRs: #2 (HLD+deep-
dive+LLD) and #3 (OpenAPI) are **merged**; #4 (ADRs+diagrams+runbook) is **open**. The next engineer
can implement the Phase 2 walking skeleton straight from `docs/lld.md` + `docs/api/openapi.yaml`.

## References (read these first)
- **Implementation plan (per-phase):** [`../implementation-plan/README.md`](../implementation-plan/README.md) · Phase 1 detail: [`../implementation-plan/phase-1-design.md`](../implementation-plan/phase-1-design.md)
- **Delivery plan (what/when):** `private/Build-Delivery-Plan-v3.md` ← **now the source of truth** (supersedes v2; 9 ADRs, comparative trade-offs elevated)
- **Design source-of-truth (architecture):** `private/Design-Doc-v3-Consolidated.md` + `private/Component-Deep-Dive.md`
- **Approved Phase 1 execution plan:** `~/.claude/plans/take-a-look-at-groovy-hammock.md`
- **Prior handoff:** [`phase-0-handoff.md`](phase-0-handoff.md)
- **Repo guidance:** `CLAUDE.md` (tracked) · `CLAUDE.local.md` (private)

## What shipped in Phase 1 (all under public `docs/`)
- **`docs/hld.md`** — Design Doc v3 dropped in as the repo's public High-Level Design (four tiers,
  the §5 A/B/C fork, decision records, data shapes). One allusion softened ("the prompt's…" → "the
  requirement for…").
- **`docs/component-deep-dive.md`** — per-component companion (responsibility · in/out · internals ·
  tech · failure · scale + edge catalog + end-to-end flows). Verbatim drop-in, already clean.
- **`docs/lld.md`** — the implementation contract: module map, Postgres **DDL** (`events`,
  `aggregates`, `serving_rows` + `serving_active_version` pointer, `tenant_config`), the `/api/v1`
  contract, the **seam interfaces with Java signatures** (`Forecaster`, `ForecastProvider`,
  `InsightGenerator`, repos), the 7-step read pipeline + `fresh|stale|pending|degraded` degradation
  chain, idempotency (dedupe gate + additive upsert = exactly-once effect), Redis cache (versioned
  keys + jitter + single-flight), forecast batch, profile wiring, tenant scoping, sizing, UI
  contract, RFC-7807 errors.
- **`docs/api/openapi.yaml`** — OpenAPI 3.1; `POST /api/v1/events` + `GET …/top-categories`;
  `SaleEvent`/`IngestAck`/`TopKResponse`/`Problem` schemas; **passes `redocly lint` (exit 0)**.
- **`docs/adr/0001-0009` + `README.md`** — 9 ADRs in the comparative format (A/B/C fork + DR-1…DR-8).
- **`docs/diagrams/`** — `architecture`, `data-flow`, `erd`, `read-sequence`, `ui-flow` (Mermaid).
- **`docs/runbook.md`** — SLOs, alarms, degradation procedures, replay/recovery, deploys.
- **Housekeeping commit** — repointed `CLAUDE.md` + the `phase-status` skill at `Build-Delivery-Plan-v3`.

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| Source of truth | Delivery plan **v3** | `private/Build-Delivery-Plan-v3.md`; CLAUDE.md + phase-status skill repointed |
| Design docs | HLD + deep-dive are **drop-ins** of the private docs (sanitized) | not re-authored |
| ADRs | **9**, all comparative (options→pros/cons→decision→why→if-changes→consequences) | heavily scored — keep this format |
| API base path | `/api/v1` | read: `GET /tenants/{id}/top-categories?mode&window&k` |
| Modes / windows | `mode=forecast\|actuals`, `window=week\|month\|year` (`quarter` reserved) | canonical `actuals` (plural) |
| Serving table | versioned rows + `serving_active_version` pointer → atomic swap / rollback | local Postgres; DynamoDB in `aws` |
| Idempotency | `events.idempotency_key` UNIQUE = dedupe gate; aggregates upsert additively | exactly-once effect |
| Cache key | `topk:{tenant}:{ver}:{window}:{mode}:{k}`; per-tenant `tenantver` bump invalidation | jittered TTL + single-flight |
| Errors | RFC 7807 `application/problem+json` | degraded read = `200` + `status`, never 5xx |
| Git workflow | **branch-per-stage**, one PR per artifact group | replaces the Phase-0 open question |

## How to build / run / verify
Phase 1 is **docs-only** — no `service/` code touched, so the Phase 0 build guarantees are unaffected
(not re-run this phase). Verification commands used:
```bash
npx @redocly/cli lint docs/api/openapi.yaml      # exit 0 (1 intentional localhost warning)
grep -rin -E 'intuit|quickbooks|...' docs/        # hygiene: clean (only "intuitive")
/public-repo-check                                # GO — no secrets / no employer framing
```
Diagrams: confirm they render in GitHub's Mermaid preview on PR #4 (no PNG export — deferred).

## Gotchas / non-obvious
- **The private design docs were already public-clean** — only one allusion needed softening. Future
  drop-ins still must pass `/public-repo-check`; "intuit**ive**" and CLAUDE.md's own policy text are
  known false positives in the employer-name grep.
- **redocly emits 1 warning** (`no-server-example.com`) on the `localhost` server URL — intentional
  for a local-first demo; exit code is still 0, so the "OpenAPI validates" gate passes.
- **Mode is `actuals` (plural)** in the canonical contract; the old Phase-2 plan text said
  `mode=actual` (singular) once — use the OpenAPI/LLD spelling.
- **LLD adds an explicit `serving_active_version` pointer table** (not in the raw design doc) to make
  the "atomic swap + rollback" concrete in Postgres — carry this into the Phase 3/4 schema.

## Git / PR state
- `main`: includes the v3-pointer chore (`08211eb`), Stage 1 (`cb6ad63`, merged via PR #2 `c80a3d1`),
  and Stage 2 (PR #3, **merged**).
- **PR #4** (`docs/phase1-adr-diagrams-runbook` → `main`): Stage 3, **OPEN** —
  <https://github.com/heminjoshi/sales-forecasting-platform/pull/4>. Merge to finalize Phase 1.
- Current local branch: `docs/phase1-adr-diagrams-runbook`.

## Carried forward from prior phases (Phase 0 reconciliation)
- ✅ **Merge PR #1 + branch strategy** — RESOLVED. PR #1 merged by the user; adopted **branch-per-
  stage** (one PR per artifact group) for the rest of the project.
- ✅ **Lock a PR-body template** — effectively done: PRs #2–#4 use a consistent body (What's here ·
  Acceptance · Next). Not formalized into a doc; treat the PR bodies as the de-facto template.
- ✅ **Rename `/export` skill** (writes a readable `.txt` to repo root, leak risk) — RESOLVED:
  renamed to `/save-session` and committed in PR #5.
- **Earlier acceptance re-checked:** Phase 1 changed only `docs/` + two skill/CLAUDE pointers; no
  `service/`, CI, or Docker files touched → Phase 0's "modules compile / CI green / `make run` boots"
  guarantees stand (no regression introduced; not re-run).

## Next: Phase 2 — Walking skeleton (vertical slice) ⭐ DEMO
First end-to-end code, no forecasting/AI yet. Per `Build-Delivery-Plan-v3` Phase 2:
- `topsales-common`: domain (`SaleEvent`, `AggregateRow`/`CategoryAggregate`, `TopKResponse`, DTOs),
  config, repository ports.
- `topsales-ingestion`: `POST /api/v1/events`, idempotent additive upsert, raw-log append.
- `topsales-api`: `GET /api/v1/tenants/{id}/top-categories?mode=actuals&window=month&k=10`.
- `TenantScopeFilter`; **Flyway** migrations `V1__events.sql … V4__tenant_config.sql` (DDL is in
  `docs/lld.md` §2 — implement it as-is).
- Minimal static dashboard (HTML + JS + Chart.js CDN) in `topsales-api/src/main/resources/static/`.
- Postman: post events → read actuals top-k.

**Acceptance ⭐:** `docker-compose up` + `make run` → POST events → **dashboard shows actuals top-k**.
**First concrete step:** draft `../implementation-plan/phase-2-walking-skeleton.md`, then scaffold the
Flyway migrations from `docs/lld.md` §2.

## Open items / decisions pending
- [x] Merge **PR #4** to finalize Phase 1. — merged.
- [x] (carried) Rename `/export` skill — done: renamed to **`/save-session`** (no longer collides with
      the built-in `/export`); committed in **PR #5** (`chore/track-skills`).
- [x] Commit the `.claude/skills/handoff/` skill — done in **PR #5** (now tracked).

## ⭐ Work done outside the plan / repo
- **Delivery plan bumped to v3 mid-session** (by the user): ADRs 8→9, comparative trade-offs elevated,
  HLD/deep-dive became drop-ins. The execution plan was refined to match before building.
- **Repointed `CLAUDE.md` + `.claude/skills/phase-status/SKILL.md`** from `Build-Delivery-Plan-v2` →
  `v3` (a tracked, public housekeeping change — committed as a `chore` on the Stage-1 branch).
- **Adopted branch-per-stage + one PR per artifact group** (resolves the Phase-0 git-workflow open
  question) — three PRs (#2/#3/#4) instead of one Phase-1 PR.
- **`.claude/skills/handoff/`** remains **untracked** (predates this session) — left as-is; see open items.
- **Off-repo:** none this phase.
