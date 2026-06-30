# Phase 2 — Walking Skeleton (vertical slice + minimal UI)  ⭐ DEMO

> Built from the approved execution plan `~/.claude/plans/yes-this-looks-good-eager-boot.md`.
> Outcome handoff: [`../handoff-docs/phase-2-handoff.md`](../handoff-docs/phase-2-handoff.md). **✅ done.**

## Objective & acceptance
Prove the pipeline end-to-end on the **actuals** path — no forecasting / AI / cache (those are
Phases 3–5). Implements the contract in `docs/lld.md`.

**Acceptance ⭐:** `make up` + `make run` → POST `/api/v1/events` → idempotent Postgres aggregates →
GET `…/top-categories?mode=actuals` → served dashboard shows the ranked top-k. **Met** (verified live).

## Current state at start
Phases 0–1 merged: Maven multi-module scaffold (empty modules), local Docker stack, CI, and the full
design suite (`docs/lld.md` is the implementation contract). No `service/` business code yet.

## Decisions locked this phase
- **Single bootable app:** `topsales-api` depends on `topsales-ingestion` + `topsales-common` as
  libraries and component-scans `com.topsales`, so one `make run` serves ingest + read. Ingestion
  JDBC repos are `@Profile("local")` `@Repository` beans, autowired by interface (no concrete cross-
  module imports → the api/ingestion slices stayed parallelizable).
- **Migrations ship in `topsales-common`** (`src/main/resources/db/migration`, V1–V5) so the bootable
  app and every module's Testcontainers IT resolve one schema from the classpath.
- **Window range:** trailing calendar-day window ending today in the tenant tz (week=7, month=30,
  year=365), summed per category, ranked desc, stable tie-break by category id.
- **`mode=forecast` → `status=pending`** floor (reuses the actuals aggregation) — a one-line nod to
  the degradation chain; the full chain is Phase 4.
- **Insight = null** in Phase 2 (the `InsightGenerator` layer is Phase 5).

## Steps (as executed)
1. **Foundation (orchestrator):** POM deps + Testcontainers BOM/Failsafe, Flyway V1–V5 verbatim from
   LLD §2, the entire `topsales-common` contract (records, wire-enums, ports, seam interfaces),
   `application.yml`, + common unit tests. Committed as the shared base.
2. **Worker A — `topsales-ingestion`:** controller, service (validate → tenant config → tenant-local
   bucket → dedupe → additive upsert), JDBC repos, raw log, quarantine + unit/IT tests.
3. **Worker B — `topsales-api`:** `TenantScopeFilter`, `TopCategoriesController`, `ActualsService`,
   RFC 7807 `ProblemDetail`, the bootable app + unit/IT tests.
4. **Worker C — dashboard + Postman:** static `index.html`/`app.js`/`styles.css` (Chart.js CDN) +
   `postman/TopSales.postman_collection.json` with assertions.
5. **Integration/repair pass (orchestrator):** full reactor build, Jackson-3 classpath cleanup, the
   `spring-boot-flyway` fix (found via live smoke), and the live end-to-end smoke test.

(Orchestrated as 1 orchestrator + 3 subagents on disjoint modules; the shared contract was built
first to keep the fan-out conflict-free.)

## Acceptance checklist
- [x] `make up` + `make run` boots; Flyway migrates 5 versions at startup.
- [x] POST single/batch/duplicate → `202` with correct `received/applied/deduped/quarantined`.
- [x] GET actuals → ranked top-k, `status=fresh`, `asOf`; signed RETURN nets; additive sums.
- [x] `mode=forecast` → `status=pending`; tenant mismatch → 403; unknown tenant → 404; bad k → 400.
- [x] Dashboard served at `/` and renders the top-k.
- [x] 38 unit tests green (`make test`). Testcontainers `*IT`s written (CI; see handoff gotcha).

## Out of scope / deferred
Redis/cache (P4), forecast batch + serving reads + full degradation chain (P3–P4), GenAI insight
(P5), Micrometer/observability (P6), data generator + one-command seeded demo + full IT suite (P8).
