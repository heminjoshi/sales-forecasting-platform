# Phase 4 — Handoff

> **Status:** ✅ Complete & verified live — on branch **`feat/phase4-forecast-serving-cache`**,
> **not yet committed / no PR** (working tree changes; commit when ready).
> Covers the **forecast read path**, the **degradation chain**, the **Redis read cache** + event-driven
> invalidation, and the **dashboard** status/forecast surfacing. Hand-off for whoever picks up **Phase 5**.
> **Date:** 2026-06-29 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
`mode=forecast` is now wired end-to-end. The read path reads **precomputed serving rows** through a new
`ForecastProvider`, fronts them with a **Redis cache** (per-tenant version keying, jittered TTL,
single-flight, **fail-open**), and falls down a **4-tier degradation chain** that never fails closed:
fresh serving rows (`fresh`) → aged last-good (`stale`) → in-JVM **seasonal-naive from actuals**
(`degraded`) → actuals top-k floor (`pending`). The forecast batch now **bumps the per-tenant cache
version** after each swap so new forecasts invalidate the cache at once. The dashboard renders the
status badge (4 states), confidence chips, prediction-interval error bars, and an honest degraded/pending
banner. **Headline acceptance met live:** wiping the serving table → the dashboard still renders,
flagged `degraded`. Full reactor `make test` green (**110 tests**: common 8, ingestion 12, datagen 9,
forecast 39, api 42); all read scenarios verified against real Postgres + Redis.

## References (read these first)
- **Implementation plan:** [`../implementation-plan/README.md`](../implementation-plan/README.md) · Phase 4: [`../implementation-plan/phase-4-forecast-serving.md`](../implementation-plan/phase-4-forecast-serving.md)
- **Delivery plan (source of truth):** `private/Build-Delivery-Plan-v3.md` (Phase 4 now ✅)
- **Design / contract:** `private/Design-Doc-v3-Consolidated.md` + public **`docs/lld.md`** (§5 read pipeline & degradation, §7 cache, §13 UI) · **`docs/adr/`** (0001 data-plane, 0010 channel)
- **Approved execution plan:** `~/.claude/plans/come-up-with-a-jazzy-eagle.md` (Phase-4 plan + the future time-series-overlay design)
- **Prior handoffs:** [`phase-0`](phase-0-handoff.md) · [`phase-1`](phase-1-handoff.md) · [`phase-2`](phase-2-handoff.md) · [`phase-3`](phase-3-handoff.md)
- **Repo guidance:** `CLAUDE.md` (tracked) · `CLAUDE.local.md` (private)

## What shipped

### Foundation & wiring (`topsales-api` ← `topsales-forecast`, Redis)
- **`topsales-api` now depends on `topsales-forecast`** to reuse `SeasonalNaiveForecaster` for the
  fallback. Forecast pom gained `<classifier>app</classifier>` (plain library jar, like datagen) and
  marks its `topsales-datagen` dep **`<optional>true</optional>`** so the generator doesn't leak onto the
  read classpath.
- **Component-scan exclude.** `TopSalesApplication` now uses an explicit `@ComponentScan` (Boot's two
  default filters **+** a regex exclude of `com.topsales.forecast.*`) so the API never boots the batch's
  `ForecasterJob`/`BatchConfig`/`ForecastWiring` beans. The read path builds the one forecaster it needs
  explicitly in `ReadWiring`.
- **`spring-boot-starter-data-redis`** added to both `topsales-api` and `topsales-forecast`;
  `spring.data.redis.*` (localhost:6379, `timeout: 500ms`) in both `application.yml`. Lettuce connects
  lazily → both apps boot with Redis down.
- **`config/ReadWiring`** beans: `ServingTableRepository` (= `new JdbcServingTableRepository(jdbc, versionKeep)`,
  constructed explicitly — still **not** `@Repository`), `ForecastProvider` (`PrecomputedForecastProvider`),
  and the `SeasonalNaiveForecaster` fallback (from `topsales.forecast.*`).

### Forecast read path + degradation chain (`docs/lld.md` §5)
- **`provider/PrecomputedForecastProvider`** — `getTopK(query)` = `servingRepo.readActive(ServingKey.of(...))`;
  pure adapter, empty `Optional` drives degradation.
- **`service/ForecastReadService`** — the ladder. `fresh` iff `now − asOf ≤ forecast.freshness-slo` (36h),
  else `stale`; maps serving rows → top-`k` `TopKItem`s (interval only when both bounds present);
  provider call wrapped so any `RuntimeException` is treated as a miss. Falls to the fallback, then to
  `ActualsService` relabeled `pending` (which still throws `UnknownTenantException` → 404).
- **`service/SeasonalNaiveFallback`** — `tryDegraded(query)`: loads `history-days` of actuals, groups by
  category, runs `SeasonalNaiveForecaster` at horizon = window days, ranks top-k, marks `degraded` with
  `confidence=LOW`, **no interval / no delta** (explicitly uncertain). Empty history → empty → `pending` floor.
- **`web/TopCategoriesController`** — forecast mode → `cacheShell.getOrCompute(query, () -> forecastReadService.handle(query))`;
  actuals mode → `ActualsService` (always `fresh`, cache-bypassed). All 400/403 validation unchanged.

### Cache + event-driven invalidation (`docs/lld.md` §7)
- **`common/cache/CacheKeys`** (shared, pure): `tenantver:{tenant}`, `topk:{tenant}:{ver}:{window}:{mode}:{channel}:{k}`,
  `{key}:lock`. **`{channel}` added** to the §7 key to avoid cross-channel collisions (Phase 2.5).
- **`api/cache/CacheShell`** (interface) + **`NoOpCacheShell`** (pass-through default/test double) +
  **`RedisCacheShell`** (`@Component @Primary`): cache-aside get→miss→single-flight lease (`SET NX PX lock-ttl`,
  follower poll-then-fall-through)→compute→put with **jittered TTL** (`base-ttl ± jitter-pct%`), **full
  fail-open** (any Redis fault → log-once + run the supplier; supplier exceptions propagate, never cached).
- **Batch bump:** `forecast/batch/CacheVersionBumper` (+ `RedisCacheVersionBumper`, no-ops on Redis fault);
  `ForecasterJob.runTenant` calls `bump(tenant)` after the 9 serving writes → `INCR tenantver:{tenant}`.
- **Config:** `TopsalesProperties.Cache` gained `lockTtl` (now `(baseTtl, jitterPct, lockTtl)`); yml
  `topsales.cache.lock-ttl: 2s`.

### Dashboard + Postman (`docs/lld.md` §13)
- `static/{index.html,app.js,styles.css}`: **status badge** (fresh=green, stale=amber, degraded=orange,
  pending=grey) + human `asOf`; **degraded banner** ("on-the-fly seasonal-naive estimate, low confidence")
  and a soft **pending** note; **confidence chips**; **prediction-interval error bars** via a small inline
  Chart.js `afterDatasetsDraw` plugin (plain bars when intervals absent); graceful drop of the From–To
  scope label when `windowFrom/To` are null.
- `postman/TopSales.postman_collection.json`: a **forecast (fresh)** request + a documented
  **"wipe forecast table" degradation** scenario asserting `200` + `degraded`/`pending` (never 5xx).

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| Degradation ladder | 4 tiers, never fails closed (§5) | serving fresh→`fresh` · serving aged→`stale` · seasonal-naive from actuals→`degraded` · actuals floor→`pending` |
| Freshness SLO | `fresh` iff `now − asOf ≤ forecast.freshness-slo` | default **36h**; future `asOf` also fresh |
| Degraded items | value + `confidence=LOW`, **no** interval/delta | honest "uncertain, computed now"; `asOf=now` |
| Forecast windowFrom/To | **null** for serving/degraded tiers (UI drops the range) | deviation from P3 handoff's "forward window" suggestion — see Outside-the-plan |
| Cache key | `topk:{tenant}:{ver}:{window}:{mode}:{channel}:{k}` | **channel added** vs literal §7; `ver` = `tenantver:{tenant}` (absent⇒0) |
| Invalidation | per-tenant version **bump** by the batch (`INCR`) | O(1), no key scan; old-`ver` keys orphan + TTL-expire |
| Cache resilience | jittered TTL + single-flight lease + **fail-open** | accelerator only; Redis down ⇒ reads still 200 (uncached); supplier exceptions never cached |
| Forecast reuse | `topsales-api` depends on `topsales-forecast` (classifier `app`, datagen optional) | + `@ComponentScan` excludes `com.topsales.forecast.*` so the batch never boots in the API |
| CacheShell seam | interface; `RedisCacheShell @Primary`, `NoOpCacheShell` fallback/test | lets local↔(designed DynamoDB) and tests swap cleanly |
| Actuals mode | unchanged — always `fresh`, **cache-bypassed** | only the forecast path is cached/degraded |

## How to build / run / verify
```bash
make test         # full reactor unit tests, no Docker — green (110 tests)
make up           # Postgres + Redis (wait healthy)
make seed         # backfill seasonal, channel-split history (t_demo + t_acme)
make forecast     # batch: writes 18 serving pks + bumps tenantver:{t_demo,t_acme}
make run          # api on :8080 — open http://localhost:8080
```
**Live-verified (2026-06-29, real Postgres + Redis):**
- **fresh:** `GET …/top-categories?mode=forecast&window=month&k=5` (`X-Tenant-Id: t_demo`) → `status:"fresh"`,
  items carry `deltaVsPrior`+`confidence`(HIGH/MEDIUM)+`interval`; cache key `topk:t_demo:1:month:forecast:all:5`
  written; repeat call is a cache hit. `windowFrom/To` omitted.
- **degraded (headline):** `TRUNCATE serving_rows, serving_active_version;` + `redis-cli INCR tenantver:t_demo`
  → `status:"degraded"`, value + `confidence:LOW`, no interval — **dashboard still renders**.
- **fail-open:** `docker stop topsales-redis` → read still `200` (degraded, uncached); `docker start` to restore.
- **actuals:** `mode=actuals` → `status:"fresh"`, trailing `windowFrom` set, cache-bypassed. **403** cross-tenant, **400** bad `k` preserved.
- **invalidation:** batch bumped `tenantver` (t_demo 1→3, t_acme 1→2 across reruns); reads compute the key off the current version.
- `make forecast` reran to restore serving rows → `status:"fresh"` again (system left in a clean demo state; API running on :8080).

## Gotchas / non-obvious
- **A stale API instance can hold :8080.** A prior session's `spring-boot:run` was still listening, so a new
  `make run` silently failed to bind and curl hit the OLD (Phase-2) behavior (`mode=forecast`→`pending`).
  If forecast reads look wrong, `lsof -nP -iTCP:8080 -sTCP:LISTEN` and kill the stale java first.
- **`mvn -pl topsales-api test` *without* `-am` can fail** on the new `CacheKeys` if the installed
  `topsales-common` jar predates it. Use `-am` (or `make test` / a clean reactor build) so `common` rebuilds first.
- **Component-scan exclude is load-bearing.** Removing the `com.topsales.forecast.*` exclude in
  `TopSalesApplication` makes the API boot the batch (`ForecasterJob` is an `ApplicationRunner`) and clash on
  bean defs. Keep the two Boot default filters when editing that `@ComponentScan`.
- **`JdbcServingTableRepository` is still a plain class** (not `@Repository`) — the read side constructs it in
  `ReadWiring` with `versionKeep` from config (carried-forward Phase-3 trap, now resolved for reads).
- **Cache fail-open must not swallow supplier exceptions** — `RedisCacheShell` calls `compute.get()` exactly
  once outside any Redis catch so `UnknownTenantException` (404) still propagates and is never cached.
- **Jackson 3 in the cache.** `RedisCacheShell` injects Boot 4.1's `tools.jackson.databind.ObjectMapper`
  (not the Jackson-2 mapper); the `non_null` inclusion means absent fields round-trip correctly.
- **Carried Spring Boot 4.1 + Testcontainers traps still apply** (Phase 2/3 handoffs): `spring-boot-flyway`;
  standalone MockMvc only; `*IT`s are CI-only on this dev host (Docker API mismatch) — `make test` is the gate.

## Git / PR state
- Branch **`feat/phase4-forecast-serving-cache`** off `main` (which now carries Phases 2.5+3 via merged PR #7).
- **Uncommitted** — working tree changes only, no commits/PR yet. Changed/added (Phase 4):
  - **common:** `config/TopsalesProperties.java` (Cache.lockTtl), `cache/CacheKeys.java` (new).
  - **api:** `TopSalesApplication.java`, `web/TopCategoriesController.java`, `pom.xml`, `application.yml`,
    new `provider/PrecomputedForecastProvider`, `config/ReadWiring`, `service/{ForecastReadService,SeasonalNaiveFallback}`,
    `cache/{CacheShell,NoOpCacheShell,RedisCacheShell}`; tests `service/ForecastReadServiceTest`, `cache/{CacheKeysTest,RedisCacheShellTest}`,
    edited `web/TopCategoriesControllerTest` + `service/ActualsServiceTest` (Cache 3-arg).
  - **forecast:** `pom.xml`, `application.yml`, `batch/ForecasterJob.java`, new `batch/{CacheVersionBumper,RedisCacheVersionBumper}`; edited `batch/ForecasterJobTest`.
  - **ui/postman:** `static/{index.html,app.js,styles.css}`, `postman/TopSales.postman_collection.json`.
- **Suggested next:** commit per slice (foundation → read+degradation → cache → ui), open a Phase-4 PR to `main`.

## Carried forward from prior phases (reconciliation)
- ✅ **`classifier=app` on `topsales-forecast`** (Phase-3 open item) — **done**; `topsales-api` now depends on it.
- ✅ **Serving-repo wired from config on the read side** (Phase-3 noted) — **done** in `ReadWiring`.
- ✅ **`ForecastProvider` + degradation chain + Redis cache** (the whole "Next: Phase 4" from the Phase-3 handoff) — **delivered**.
- ↪️ **Deviation:** Phase-3 handoff suggested populating `windowFrom/To` with the forward forecast window;
  shipped as **null** for serving/degraded tiers (UI drops the range cleanly) — cheap to add later, see below.
- **Earlier acceptance re-verified:** `make test` green across all modules; `make run` boots; actuals path
  unchanged; `make forecast`/`make eval` still work. No regressions found.
- **Still open from prior phases:** off-repo recruiter reply (WS-G, not code); optional Testcontainers/docker-java
  pin so `*IT`s run on Engine ≥29 hosts (CI green); `make demo` (Postman/newman runner) still a Phase-8 placeholder
  (the forecast + wipe Postman requests now exist, ready for it).

## Next: Phase 5 — GenAI insight layer (WS-D, "AI-READY")
Per `Build-Delivery-Plan-v3` Phase 5 + `docs/lld.md` §9:
- `topsales-insight`: `InsightGenerator` + `TemplateInsightGenerator` (deterministic floor, local) +
  designed `BedrockInsightGenerator` (real when creds/profile present).
- **Grounding + validation:** prompt carries only computed figures; output validated to contain only those
  numbers; lazy + cached (reuse the §7 cache); cheap model id from config; prompt-injection handling (category
  names untrusted); timeout/circuit-breaker → template.
- Wire the populated `insight` field into `TopKResponse` (currently always `null`) and render it on the dashboard.
- **First concrete step:** define `InsightRequest` (numbers-only) + `TemplateInsightGenerator`, wire it into
  the read pipeline behind a lazy/cached call, with a unit test asserting the template floor + an injection probe.

## Open items / decisions pending
- [ ] **Commit + open a Phase-4 PR** to `main` (branch is uncommitted).
- [ ] Optional enhancement: **true forecast-vs-actual time-series overlay** (designed below, deferred).
- [ ] Optional: populate forecast `windowFrom/To` with the forward window if the UI scope label wants the range.
- [ ] Off-repo (WS-G, carried): recruiter reply.
- [ ] Optional: pin Testcontainers/docker-java so `*IT`s run on Engine ≥29 hosts (CI green).
- [ ] Phase 8: wire `make demo` (newman) over the now-present forecast + wipe Postman requests.

## ⭐ Work done outside the plan / repo
- **Documented (not built) future enhancement — true forecast-vs-actual time-series overlay.** Per user
  request, the approved plan captures how a per-category line chart (historical actuals + forecast band) would
  land: persist **per-day** forecast points (new `V8__forecast_series.sql` written by `ForecasterJob`; today's
  rows store only the window-sum), a new `GET …/categories/{cat}/series` endpoint + DTOs, same 4-tier ladder
  emitting a per-day series, and a Chart.js line+band UI. **TODO for the next session:** add a short
  "designed extension" note to public `docs/lld.md` §13 (the plan asks for it; not yet written into the tracked doc).
- **Cache key refined vs the design doc:** added `{channel}` to the §7 `topk:` key (the literal §7 example
  omits it) — correctness fix for the Phase-2.5 channel dimension. Worth syncing into `docs/lld.md` §7.
- **`windowFrom/To` deviation** from the Phase-3 handoff's forward-window suggestion (null for serving/degraded
  tiers) — see Locked decisions.
- **Orchestration:** built via a **Wave-0 foundation (me) + 3 parallel subagents** (A degradation chain, B Redis
  cache + batch bump, C dashboard + Postman) with clean file-ownership partitioning to avoid Maven `target/`
  races; B held until A finished (both compile `topsales-api`). Per the user's explicit request to use subagents.
- **No tracked docs synced yet** (README built-vs-designed, `CLAUDE.md` status line, `docs/lld.md` §7/§13,
  `docs/api/openapi.yaml`) — do this alongside the Phase-4 commit/PR.
- **Off-repo:** none.
