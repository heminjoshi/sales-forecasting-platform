# Phase 2 — Handoff

> **Status:** ✅ Complete & verified (PR #6 open for review). Hand-off for whoever picks up **Phase 3**.
> **Date:** 2026-06-28 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
The walking skeleton is live and runnable. The **actuals** path works end-to-end: `POST /api/v1/events`
→ idempotent per-tenant category aggregates in Postgres (Flyway-migrated) → `GET …/top-categories`
→ ranked top-k over the REST API → served dashboard. 38 unit tests green; the path was verified live
against real Postgres. No forecasting/AI/cache yet. The next engineer can build the Phase 3 forecasting
engine straight from `docs/lld.md` §8 against the `Forecaster`/`ForecastProvider` seams already stubbed
in `topsales-common`.

## References (read these first)
- **Implementation plan:** [`../implementation-plan/README.md`](../implementation-plan/README.md) · Phase 2 detail: [`../implementation-plan/phase-2-walking-skeleton.md`](../implementation-plan/phase-2-walking-skeleton.md)
- **Delivery plan (source of truth):** `private/Build-Delivery-Plan-v3.md` (Phase 2 now ✅)
- **Design / contract:** `private/Design-Doc-v3-Consolidated.md` + the public **`docs/lld.md`** (DDL §2, API §3, seams §4, pipeline §5, ingestion §6, errors §14)
- **Approved Phase 2 execution plan:** `~/.claude/plans/yes-this-looks-good-eager-boot.md`
- **Prior handoffs:** [`phase-0-handoff.md`](phase-0-handoff.md) · [`phase-1-handoff.md`](phase-1-handoff.md)
- **Repo guidance:** `CLAUDE.md` (tracked) · `CLAUDE.local.md` (private)

## What shipped in Phase 2
- **`topsales-common`** — domain records (`SaleEvent`, `AggregateRow`, `AggregateDelta`, `TenantConfig`,
  `TopKQuery`, `TopKResponse`, `TopKItem`, `Interval`, `ServingRow`/`ServingResult`); wire-enums
  (`Window`/`Mode`/`Status` lowercase via `@JsonValue`/`@JsonCreator`; `EventType`/`Confidence`
  uppercase); repository ports (`AggregateRepository`, `EventLedger`, `TenantConfigRepository`);
  seam interfaces (`Forecaster`, `ForecastProvider`, `InsightGenerator`) **stubbed for Phases 3–5**;
  **Flyway V1–V5** on the classpath (events, aggregates, serving, tenant_config, quarantine).
- **`topsales-ingestion`** — `IngestionController` (`POST /api/v1/events`, single or batch),
  `IngestionService` (validate → resolve tenant config → tenant-local bucket → dedupe gate → additive
  upsert), JDBC repo impls (`@Profile("local")` `@Repository`), NDJSON raw log, quarantine.
- **`topsales-api`** — `TenantScopeFilter`, `TopCategoriesController` (`GET …/top-categories`),
  `ActualsService`, `ApiExceptionHandler` (RFC 7807 `ProblemDetail`); **the one bootable app**
  (`@SpringBootApplication(scanBasePackages="com.topsales")`).
- **Dashboard** (`topsales-api/.../static/`) — `index.html` + `app.js` + `styles.css`, Chart.js via CDN,
  served same-origin; status badge, ranked table, bar chart, explicit loading/empty/error states.
- **`postman/TopSales.postman_collection.json`** — post single/batch/dedupe + read actuals, with assertions.
- **Build wiring** — `spring-boot-flyway`, Failsafe→verify, Testcontainers BOM 1.21.3, Jackson-3
  classpath cleanup; `Makefile` split (`test` vs `verify`); `docs/runbook.md §7`; rawlog gitignored.

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| App topology | one bootable app = `topsales-api` aggregating the others as libraries | one `make run` serves ingest + read |
| Bean wiring | ingestion JDBC repos are `@Profile("local")` `@Repository`, autowired by interface | no concrete cross-module imports |
| Migrations | live in `topsales-common` `db/migration` (V1–V5), classpath-shared | bootable app + every IT see one schema |
| Auth (local) | tenant from `X-Tenant-Id` header; body tenantId ignored (§11); path≠authed → 403 | dev stand-in for real auth |
| Window | trailing calendar days ending today in tenant tz (week=7, month=30, year=365) | summed per category, ranked desc |
| forecast mode (P2) | reuses actuals aggregation, relabels `status=pending` | full degradation chain is Phase 4 |
| insight (P2) | `null` (field present, omitted) | `InsightGenerator` lands Phase 5 |
| Errors | Spring `ProblemDetail` → `application/problem+json` | 400/403/404; degraded read = 200 + `status` |
| Money/time | `BigDecimal` money, `Instant` wire time, tenant-local `LocalDate` bucket | minor-unit-safe |

## How to build / run / verify
```bash
make test                              # 38 unit tests, no Docker — green everywhere
make up                                # Postgres + Redis (wait healthy)
make run                               # builds + boots api on :8080; Flyway migrates V1–V5 at startup
# seed + read (tenant t_demo is seeded by V4):
curl -H "Content-Type: application/json" -H "X-Tenant-Id: t_demo" -X POST localhost:8080/api/v1/events \
  -d '{"orderId":"o1","categoryId":"Office Supplies","amount":120.00,"currency":"USD","eventType":"SALE","eventTime":"2026-06-20T14:03:00Z"}'
curl -H "X-Tenant-Id: t_demo" "localhost:8080/api/v1/tenants/t_demo/top-categories?mode=actuals&window=month&k=10"
open http://localhost:8080            # dashboard
make down
```
**Live-verified results:** Electronics = 500 − 50 (RETURN nets) = 450; Office Supplies = 120 + 80 = 200
(ranked desc); duplicate idempotency_key → `applied:0/deduped:1`; `mode=forecast` → `status:pending`;
tenant mismatch → 403; unknown tenant → 404; dashboard served (`text/html`).

## Gotchas / non-obvious (Spring Boot 4.1 traps — also saved to assistant memory)
- **Flyway needs `spring-boot-flyway`.** Boot 4 modularized autoconfiguration — `flyway-core` on the
  classpath is no longer enough; without the integration module, migrations **silently never run**
  (symptom: tables missing, Hikari inits lazily on first request, no Flyway logs). This was caught by
  the live smoke, not the mocked unit tests — a reminder that the ITs / live run earn their keep.
- **Jackson 3 is the default mapper** (`tools.jackson.databind`); annotations stay
  `com.fasterxml.jackson.annotation` (shared), so DTO `@JsonValue`/`@JsonInclude` still apply. Use the
  Jackson-3 `JsonNode` (`tools.jackson.databind.JsonNode`) for `@RequestBody` (a Jackson-2 `JsonNode`
  param → HTTP 415). java-time is built in (no `JavaTimeModule`).
- **No `@WebMvcTest`** in `starter-test`; use `MockMvcBuilders.standaloneSetup(...)`. **No
  `TestRestTemplate`**; use spring-web `RestClient` against `@LocalServerPort`.
- **Testcontainers `*IT`s can't start on this dev host.** Docker Engine 29 (Desktop 4.77) requires API
  ≥1.40; the bundled docker-java pins 1.32 → HTTP 400 ("no valid Docker environment"). `DOCKER_API_VERSION`
  was not honored. They pass in CI (older Docker, min API 1.24). `make test` (unit) is the everywhere-green
  gate; `make verify` adds the ITs. Documented in `docs/runbook.md §7`. Don't chase this locally — smoke-test instead.
- **Runtime rawlog** writes to `./data/rawlog/events.ndjson` (relative to the api module's run dir);
  gitignored. A one-command seeded demo (`make seed`/`make demo`) is still a Phase 8 placeholder.

## Git / PR state
- Branch `feat/phase2-walking-skeleton` (pushed). 6 commits off `main`:
  `c0cb0e8` foundation · `6132b19` build/Flyway/Jackson · `df1165f` ingestion · `08a383a` api ·
  `30b9a09` dashboard+Postman · `a3bdde6` README.
- **PR #6** (`feat/phase2-walking-skeleton` → `main`): **OPEN** —
  <https://github.com/heminjoshi/sales-forecasting-platform/pull/6>. Merge to finalize Phase 2.
- Prior: PRs #1–#5 merged (Phase 0/1 docs + skills chore).

## Carried forward from prior phases (reconciliation)
- ✅ **Mode `actuals` (plural)** (Phase 1 gotcha) — honored in code/API/dashboard.
- ✅ **`serving_active_version` pointer table** (Phase 1 LLD addition) — shipped in `V3__serving.sql`
  (empty until Phase 3 populates it).
- ✅ **Branch-per-stage + one PR per slice** (Phase 0/1 workflow) — continued (single Phase 2 feature
  branch, one PR).
- ✅ **`make run` two-step** (Phase 0 gotcha) — still the mechanism; now boots a real app with endpoints.
- **Earlier acceptance re-verified:** Phase 0 "modules compile / CI green / `make run` boots" still holds
  — `mvn test` green across all modules; `make run` boots and serves. CI (`mvn verify`) will additionally
  run the new ITs. No regression to Phase 1 docs (only README updated + runbook §7 appended).
- **Still open from Phase 0:** off-repo recruiter reply (WS-G) — not a code item; remains the user's.

## Next: Phase 3 — Forecasting engine (WS-B)
Per `Build-Delivery-Plan-v3` Phase 3 + `docs/lld.md` §8:
- `topsales-forecast`: implement the `Forecaster` seam — `SeasonalNaiveForecaster` +
  `HoltWintersForecaster` (level/trend/seasonal); a `ForecasterJob` batch runner that reads
  `AggregateRepository.rangeByCategory`, forecasts per `(tenant, category)` × horizons, ranks to top-k,
  and writes **versioned** `serving_rows` + flips `serving_active_version` (atomic swap).
- Cold-start handling (<1 season → trend-only; none → prior/flat + LOW confidence).
- Eval: time-series-CV backtest, **WAPE** + bias report.
- **First concrete step:** draft `../implementation-plan/phase-3-forecasting.md`, then scaffold
  `topsales-forecast` with `SeasonalNaiveForecaster` + a unit test on known series, before the batch runner.
- Note: Phase 4 (not 3) wires `mode=forecast` to read `serving_rows` via `PrecomputedForecastProvider`
  and adds the real degradation chain + Redis cache. Keep the read path untouched in Phase 3.

## Open items / decisions pending
- [ ] Merge **PR #6** to finalize Phase 2.
- [ ] Off-repo (WS-G, carried from Phase 0): reply to recruiter with onsite blocks + prep-call times.
- [ ] Optional: pin Testcontainers/docker-java so `*IT`s also run on Engine ≥29 hosts (or set
      `DOCKER_HOST`/`api.version` in a `.mvn` config) — low priority; CI is green.
- [ ] Phase 8 backfill reminder: `make seed`/`make demo` + data generator for a one-command demo.

## ⭐ Work done outside the plan / repo
- **Branch hygiene:** deleted the merged `chore/track-skills` branch (local + remote) and started Phase 2
  fresh from `main`, at the user's request.
- **Build/tooling fixes not in the phase plan** (required to make the slice run): added the
  `spring-boot-flyway` autoconfig module; bound Maven **Failsafe** to `verify`; imported the
  **Testcontainers BOM** (1.21.3); narrowed `common`'s Jackson to annotations on the compile classpath;
  added a Testcontainers caveat section to `docs/runbook.md §7`; gitignored the runtime rawlog.
- **Orchestration:** built via **1 orchestrator + 3 subagents** (foundation contract first, then
  ingestion / api / dashboard+Postman in parallel on disjoint modules), per the user's explicit request —
  a process choice layered on top of the build plan.
- **Unit-test scope expansion:** per the user, every module written from here on carries unit tests
  (38 this phase) plus Testcontainers ITs for SQL — beyond the plan's lighter Phase-2 test wording.
- **Assistant memory:** saved 3 cross-session memories (Spring Boot 4.1 gotchas, the Testcontainers/Docker
  caveat, Phase 2 status) under the project memory dir. Off-repo: none.
