# Phase 3 — Handoff

> **Status:** ✅ Complete & verified — **PR #7 merged to `main`**. Covers **Phase 2.5** (channel +
> synthetic data), the **central-config** refactor, and **Phase 3** (forecasting engine) — all on one branch.
> Hand-off for whoever picks up **Phase 4**.
> **Date:** 2026-06-29 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
The platform is runnable through **Phase 3**. On top of the Phase 2 actuals slice we added: `channel`
(`ONLINE`/`OFFLINE`) as a **first-class key dimension** + a deterministic seasonal **synthetic-data
generator** (`make seed`/`make trickle`), a **central config** surface (`TopsalesProperties` + the
`/api/v1/config`-driven dashboard), and the **forecasting engine** — `Forecaster` impls (seasonal-naive
+ additive Holt-Winters, cold-start dispatch, intervals/confidence), a **batch** that writes
**versioned, ranked serving rows** per tenant×window×channel (atomic swap + prune, channels rolled up
to `all`), and a **WAPE/bias time-series-CV backtest** (`make eval` → committed report). The forecast
**read path is intentionally not wired** — `mode=forecast` still returns the honest `pending` floor
until Phase 4 adds `ForecastProvider` + the degradation chain. `make test` green across the reactor
(incl. 39 forecast-module tests); `make forecast`/`make eval` verified live against real Postgres.

## References (read these first)
- **Implementation plan:** [`../implementation-plan/README.md`](../implementation-plan/README.md) · Phase 2.5: [`../implementation-plan/phase-2.5-channel-and-synthetic-data.md`](../implementation-plan/phase-2.5-channel-and-synthetic-data.md) · Phase 3: [`../implementation-plan/phase-3-forecasting-engine.md`](../implementation-plan/phase-3-forecasting-engine.md)
- **Delivery plan (source of truth):** `private/Build-Delivery-Plan-v3.md` (Phases 2.5 + 3 now ✅)
- **Design / contract:** `private/Design-Doc-v3-Consolidated.md` + public **`docs/lld.md`** (§8 batch, §3 serving, §4 seams) · **`docs/adr/`** (0001 data-plane, 0004 baseline-vs-ML, 0005/0006 WAPE, 0010 channel)
- **Approved execution plans:** `~/.claude/plans/take-a-look-at-jolly-crystal.md` (the plan-doc + dashboard-polish + Phase-3 orchestration plans, iterated across the session)
- **Prior handoffs:** [`phase-0-handoff.md`](phase-0-handoff.md) · [`phase-1-handoff.md`](phase-1-handoff.md) · [`phase-2-handoff.md`](phase-2-handoff.md)
- **Repo guidance:** `CLAUDE.md` (tracked; status line updated) · `CLAUDE.local.md` (private)

## What shipped

### Phase 2.5 — channel dimension + synthetic data (ADR-0010)
- **Channel in the key.** `V6__channel.sql` widens aggregates PK → `(tenant, category, channel, day)`
  (backfill `'all'` then drop default). `Channel{ONLINE,OFFLINE}` (uppercase wire) on
  `SaleEvent`/`AggregateRow`/`AggregateDelta`; read-side `ChannelFilter{ALL,ONLINE,OFFLINE}` (lowercase,
  default `all`). API `channel=all|online|offline`; `all` for actuals = **read-time sum**.
  `AggregateRepository` gained `bulkUpsert` + channel-aware `rangeByCategory`.
- **`topsales-datagen`** (new module) — a deterministic `SeasonalityModel`
  (`base × channelShare × trend × weekly × monthly × hve × seeded-noise`, per-cell RNG keyed on
  `(globalSeed, tenant, category, channel, epochDay)`) + `HveCalendar`. `make seed` bulk-backfills,
  `make trickle` posts live events. Committed `data/seed/seed-config.json`. Features: HVE calendar
  (BF/CM/December/Prime-Day, per-channel), a sparse category, a one-off outlier, signed returns.
- **Multi-tenant demo polish** — `GET /api/v1/tenants` + a 2nd seeded tenant (`t_acme`, V7) so the
  dashboard's tenant **dropdown** is genuinely multi-tenant; `TopKResponse` echoes `channel` and carries
  `windowFrom`/`windowTo`; dashboard shows a scope label (`Actuals · Month · All — May 31 – Jun 29, 2026`).

### Central config refactor (`TopsalesProperties`)
- `@ConfigurationProperties("topsales")` in `topsales-common` binds the whole `topsales.*` tree —
  `read.{k-default,k-min,k-max,k-options,window/mode/channel-default}`, `window-days.{week,month,year}`,
  `forecast.*` (Phase-3 params), `cache.*`, `rawlog.dir`. `TopCategoriesController`/`ActualsService`/
  `JdbcEventLedger` inject it (replaced `MIN_K`/`MAX_K`, the `WINDOW_DAYS` map, the rawlog `@Value`).
- **Dashboard is config-driven** — `GET /api/v1/config` returns `kOptions`/defaults/window/channel sets;
  `app.js` builds the controls from it (`k` is now a `<select>` of `k-options`).
- Generator tunables (weekly/monthly factors, HVE multipliers, noise band, sparse rate) moved into
  `seed-config.json`.

### Phase 3 — forecasting engine (`topsales-forecast`)
- **`Forecaster` impls** (`model/`, `math/`): `SeasonalNaiveForecaster` (common-deps-only — reusable as
  the Phase-4 fallback), additive `HoltWintersForecaster`, `TrendOnly`/`SparseRate`/`FlatPrior`, and a
  `ColdStartForecaster` **dispatcher** (sparse-before-length routing). `ResidualIntervals` +
  `ConfidenceMapper` (z + relative-half-width thresholds). `config/ForecastWiring` exposes one
  `forecaster` bean from `TopsalesProperties`. **Contract:** `forecast(...)` returns `pointValue` =
  predicted **cumulative sum over the next h days** per horizon.
- **Serving writer** (`topsales-ingestion`): `JdbcServingTableRepository` implements the new
  `ServingTableRepository` port — versioned write (`max(version)+1`) → atomic `serving_active_version`
  swap → prune to `versionKeep`. Shared `ServingKey.of(tenant, window, mode, channel)` builder.
- **Batch** (`BatchApplication` + `batch/ForecasterJob`, `make forecast`): per tenant, load history →
  forecast per `(category, channel)` → roll channels up to `all` (Σ point/interval, min confidence) →
  rank each `tenant#window#mode#channel` partition (top `serving-top-n`) → `writeVersionAndSwap`.
  **9 pk writes/tenant** (3 windows × 3 channels). `delta_vs_prior` from trailing-window actuals.
- **WAPE eval** (`eval/`, `make eval`): expanding-window CV (initialTrain 84d, horizon 7d, step 7,
  ≤12 folds), pooled WAPE + bias, near-zero guard; regenerates the seed in-memory at a **fixed** window
  for byte-reproducibility. Committed `docs/forecast-eval-report.md` + `data/eval/forecast-eval.csv`.

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| Channel | first-class key dimension (ADR-0010) | PK `(tenant,category,channel,day)`; `all` = read-time sum (actuals), materialized (forecast) |
| Two channel enums | domain `Channel` (uppercase) vs read-side `ChannelFilter` (lowercase, has `ALL`) | `all` is never a stored channel value |
| Central config | `TopsalesProperties` is the single tweakable surface; dashboard reads `/api/v1/config` | add new knobs under `topsales.*`, not as constants |
| Forecaster contract | `pointValue(h)` = predicted **sum of next h days**; ignores `ctx.window()` | matches actuals trailing-window sum; batch maps horizon 7/30/365 → WEEK/MONTH/YEAR |
| Holt-Winters | **additive** (not multiplicative) | safe across zero/negative (returns + sparse) |
| Cold-start | a `ColdStartForecaster` dispatcher; flag rides on `Confidence` | sparse-before-length; no `ForecastValue` change |
| Serving writer | versioned, `max+1`, atomic active-version swap, prune to `version-keep` (3) | last-good stays readable until swap; rerun-safe |
| Serving repo wiring | `JdbcServingTableRepository` is a **plain class** (not `@Repository`) | takes `int versionKeep`; batch constructs it; api won't auto-create it (would fail to boot) |
| Batch topology | web-less `BatchApplication` (`make forecast`), one-shot `ApplicationRunner` then exit | ML never enters the read JVM (ADR-0001) |
| Eval | pure-JVM, regenerates seed in-memory at a **fixed window** (`2026-06-01`) | byte-reproducible report; regression test asserts thresholds not exact values |
| Read path (P3) | **unchanged** — `mode=forecast` still relabels `status=pending` | `ForecastProvider` + degradation chain is Phase 4 |

## How to build / run / verify
```bash
make test                 # full reactor unit tests, no Docker — green (incl. 39 forecast-module tests)
make up                   # Postgres + Redis (wait healthy)
make seed                 # backfill seasonal, channel-split history for t_demo + t_acme
make forecast             # batch: writes versioned serving rows (18 pks = 2 tenants × 9)
make eval                 # backtest → regenerates docs/forecast-eval-report.md (deterministic)
make run                  # boots api on :8080; open http://localhost:8080 (config-driven dashboard)
```
**Live-verified (2026-06-29):**
- `make seed` → 5594/5598 channel-split aggregate rows per tenant; offline top = grocery, online top = electronics (channel-differentiated).
- `make forecast` → `tenant=t_demo/t_acme series=12 pkWrites=9` each. `serving_rows`: `t_demo#month#forecast#all` rank-1 electronics **43009.05** = online **30035.75** + offline **12973.30** (exact `all` rollup), independently re-ranked per channel.
- `make eval` → HoltWinters pooled WAPE **0.0686** beats SeasonalNaive **0.0755**; dense ~0.06, sparse `cat_collectibles` ~2.0 (the baseline-beat / cold-start story). Reruns byte-identical.
- `make run` → api boots; `GET /api/v1/config` returns `kOptions:[5,7,10]`; `GET /api/v1/tenants` → `["t_acme","t_demo"]`; `top-categories?k=7` returns all 6 categories (graceful `k>N`).

## Gotchas / non-obvious
- **`JdbcServingTableRepository` must NOT be `@Repository`.** It takes a plain `int versionKeep`; with the
  bean annotations the **api app fails to start** ("No qualifying bean of type 'int'"). The batch
  constructs it explicitly in `BatchConfig`; Phase 4 will wire it from config for the read side. (Fixed
  in `2f43114` after the live api smoke caught it — the mocked unit tests didn't.)
- **`topsales-datagen` attaches its Boot jar under classifier `app`.** A Spring-Boot fat jar
  (classes under `BOOT-INF/classes`) is unusable as a **library** dependency, and `topsales-forecast`'s
  eval depends on datagen's `SeasonalityModel` at compile time. The plugin now keeps the main artifact a
  plain library jar; `make seed`/`make trickle` use `spring-boot:run` (from `target/classes`), unaffected.
  **Phase 4 will hit the same when `topsales-api` depends on `topsales-forecast`** (to reuse
  `SeasonalNaiveForecaster` for the fallback) — apply the same classifier treatment to the forecast pom.
- **Eval uses a FIXED window (`2026-06-01`), never `LocalDate.now()`** — so `make eval` and the committed
  report are byte-reproducible. The live `make seed`/`make forecast` are today-relative (trailing window).
- **Forecaster `pointValue` is a window SUM, not a daily value.** The eval recovers daily forecasts by
  requesting horizons `{1..h}` and differencing the cumulative points. Keep this contract if you add models.
- **`@ConfigurationProperties` records need every key present in yml** — nested groups bind to `null` if
  absent (NPE on access). The api + `topsales-forecast/application.yml` both carry the full `topsales.forecast.*`.
- **Carried Spring Boot 4.1 traps still apply** (see Phase 2 handoff): `spring-boot-flyway` required;
  Jackson 3 (`tools.jackson`) runtime with `com.fasterxml.jackson.annotation` annotations; standalone
  MockMvc (no `@WebMvcTest`); a hand-built Jackson-2 test mapper serializes `LocalDate` as an array unless
  you `disable(WRITE_DATES_AS_TIMESTAMPS)` (Spring's mapper already emits ISO — bit me in `TopKResponseTest`).
- **Testcontainers `*IT`s remain CI-only on this dev host** (Docker API mismatch). `make test` is the
  everywhere-green gate; the new `JdbcServingTableRepositoryIT` + `ForecasterJobIT`-style coverage run in CI.

## Git / PR state
- Branch **`feat/phase2.5-channel-and-synthetic-data`** (pushed). Key commits off `main`:
  `f0ad919` channel+generator · `557d8b0` tenant dropdown+2nd tenant · `1b83cc3` window range+scope label ·
  `80cdeb4` central config · `49cd16c` Phase-3 foundation · `441484e` forecaster+serving (wave 1) ·
  `e43eff6` batch (wave 2a) · `3ad565b` eval (wave 2b) · `2f43114` serving-repo bean fix · `6350966` docs.
- **PR #7** (`feat/phase2.5-…` → `main`): **MERGED** — <https://github.com/heminjoshi/sales-forecasting-platform/pull/7> (155 files). Phases 2.5 + config + 3 are now on `main`.
- Prior: **PR #6 (Phase 2) MERGED** (squash). PRs #1–#5 merged.

## Carried forward from prior phases (reconciliation)
- ✅ **PR #6 / Phase 2** (Phase 2 open item) — **merged** (squash) to `main`.
- ✅ **`make seed`/data generator** (Phase 2 deferred it to Phase 8) — **delivered early in Phase 2.5**
  (`make seed` + `make trickle` + `topsales-datagen`). `make demo` (Postman/newman) is still a Phase 8 placeholder.
- ✅ **`serving_active_version` populated** — Phase 2 shipped the empty table; the Phase-3 batch now
  writes versions and flips the pointer atomically.
- ✅ **Branch-per-stage + one PR per slice** (Phase 0/1 workflow) — continued (one branch, PR #7).
- **Earlier acceptance re-verified:** `make run` still boots and serves; `make test` green across all
  modules; actuals read path unchanged (channel defaults to `all`). **One regression caught & fixed**
  this phase: the serving-repo bean briefly broke api startup (`2f43114`). No other regressions.
- **Still open from Phase 0/2:** off-repo recruiter reply (WS-G, not a code item); optional Testcontainers
  pin so `*IT`s run on Engine ≥29 hosts (low priority, CI green).

## Next: Phase 4 — Forecast serving + resilience (WS-B, WS-C)
Per `Build-Delivery-Plan-v3` Phase 4 + `docs/lld.md` §5/§7/§11:
- `ForecastProvider` + `PrecomputedForecastProvider` reading the serving table via
  `ServingTableRepository.readActive(ServingKey.of(...))` (both already exist).
- Wire `mode=forecast` to the serving rows; implement the **degradation chain**: last-good (`stale`) →
  JVM **seasonal-naive from actuals** (`degraded`, reuse `SeasonalNaiveForecaster`) → actuals
  (`forecast_pending`). Populate `windowFrom`/`windowTo` with the forward forecast window.
- Redis cache (keys `(tenant,window,mode,k,channel)`, jittered TTL, event-driven invalidation, single-flight).
- UI: real forecast-vs-actual chart + confidence + status badge.
- **First concrete step:** make `topsales-api` depend on `topsales-forecast` (apply the **classifier `app`**
  fix to the forecast pom first — see gotcha), then implement `PrecomputedForecastProvider` + a read-path
  unit test that asserts the degradation ladder, before touching Redis/UI.

## Open items / decisions pending
- [x] Merge **PR #7** — done (Phases 2.5 + config + 3 are on `main`).
- [ ] Phase 4: apply the `classifier=app` fix to `topsales-forecast/pom.xml` before `topsales-api` depends on it.
- [ ] Off-repo (WS-G, carried from Phase 0): reply to recruiter with onsite blocks + prep-call times.
- [ ] Optional: pin Testcontainers/docker-java so `*IT`s run on Engine ≥29 hosts (low priority; CI green).
- [ ] Phase 8 backfill: `make demo` (Postman/newman) still a placeholder; consider a forecast-mode Postman scenario.

## ⭐ Work done outside the plan / repo
- **Central-config refactor** was **not** in any phase plan — added at the user's request as a cross-cutting
  refactor (its own subagent), landing between Phase 2.5 and Phase 3. Extended in the Phase-3 foundation
  with `topsales.forecast.*`.
- **Dashboard polish beyond the phase plans** (user-requested): the tenant dropdown + 2nd tenant, the
  window from/to + scope label, the config-driven controls, and documenting the `k>N` graceful behavior as
  an enhancement (not built).
- **Contract reconciliation:** `TopKResponse` now echoes `channel` + `windowFrom`/`windowTo`, and
  `openapi.yaml` gained `/api/v1/tenants`, `/api/v1/config`, `TenantsResponse`/`UiConfig` + required
  `SaleEvent.channel` — closing code-vs-OpenAPI drifts found mid-work.
- **Orchestration:** built via **1 config subagent + a Phase-3 foundation (orchestrator) + 4 Phase-3
  subagents** (forecaster math + serving writer in parallel; batch then eval sequentially, same module),
  per the user's explicit request. Two cross-agent integration fixes by the orchestrator: the serving-repo
  bean annotations (`2f43114`) and the datagen `app` classifier (made by the eval agent, kept).
- **Docs synced (tracked/public):** `README.md` built-vs-designed + quick start, `CLAUDE.md` status line,
  `docs/api/openapi.yaml`, `docs/forecast-eval-report.md` (generated), `data/seed/README.md`.
- **Off-repo:** none.
