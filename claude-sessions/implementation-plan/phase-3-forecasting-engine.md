# Phase 3 — Forecasting engine

> Source: `private/Build-Delivery-Plan-v3.md` §3 · Workstream **[WS-B]** · Effort **L**.
> ADRs in scope: **0001** (data-plane decoupling), **0004** (Java baseline vs SageMaker),
> **0005** (WAPE). Depends on **Phase 2.5** (channel-keyed, seasonal seed data).
>
> **✅ Done (2026-06-29).** Built via 1 foundation + 4 subagents; PR #7. Outcome handoff:
> [`../handoff-docs/phase-3-handoff.md`](../handoff-docs/phase-3-handoff.md).

## Objective & acceptance
Build the `topsales-forecast` engine: baseline forecasters behind the existing `Forecaster` seam, a
batch runner that ranks and writes **versioned** serving rows per `(tenant, category, channel)` ×
horizon (summing channel grain up to `all`), and a WAPE evaluation on the seed.

**Acceptance:** the batch produces ranked forecasts with intervals + a **WAPE report on seed data**.

## Current state at start
Seams already exist as stubs in `topsales-common/.../forecast/`: `Forecaster.forecast(history, ctx)
→ List<ForecastValue>`, `ForecastContext`, `ForecastValue`, `ServingRow`, `ForecastProvider`,
`Confidence`. Serving DDL exists (`V3__serving.sql`: `serving_rows` + `serving_active_version`,
free-text `pk`) but is **empty** — no writer. `module topsales-forecast` is an empty stub. After
Phase 2.5, aggregates are channel-keyed and the `data/seed/` dataset is committed. ADR-0001 says ML
must stay off the read path (data-plane coupling); the read path computes actuals as trailing-window
**sums** (week=7/month=30/year=365 days). `*IT` are CI-only on this host.

## Decisions locked this phase

| # | Decision | Value / why |
|---|---|---|
| 1 | Models | 🔨 `SeasonalNaiveForecaster` (season `m=7`; regularize onto a contiguous daily grid, gap-fill missing days to **0**; self-contained, `topsales-common`-deps-only — it is **reused as the Phase-4 degradation fallback**). 🔨 `HoltWintersForecaster` — **additive** (safe across the zero/negative values that returns + the sparse category produce; the trend term `b_t` absorbs the multi-month slope; defaults α/β/γ = 0.3/0.1/0.3, optional coarse grid-fit on one-step SSE). `double` for the smoothing math, round to money (scale 2, FLOOR low / CEIL high) only at the boundary. *(Multiplicative HW would divide by zero/sign-flip on this data — left as a 📐 enum hook.)* |
| 2 | Cold-start | 🔨 A `ColdStartForecaster implements Forecaster` **dispatcher** (keeps each model a clean textbook impl; the batch wires one `Forecaster` bean). Routes by `n` = non-empty days, `span`, `density`, `m=7`: `n==0`→flat-prior **LOW**; `density<~0.4`→sparse mean-rate **LOW**; `0<span<m`→trend-only; `m≤span<2m`→seasonal-naive **MED**; `span≥2m & dense`→Holt-Winters (HIGH/MED/LOW by intervals). **Evaluate sparse before length.** Flag rides on `Confidence` (no `ForecastValue` change). |
| 3 | Intervals | 🔨 In-sample residual `σ × z × horizon-growth`; `z=1.28` (~80%, dashboard-friendly); growth `√(1+⌊(h−1)/m⌋)` (naive), `√h` (HW). Relative half-width `r = zσ_h/max(|point|,ε)` → `HIGH r<0.15 / MED <0.40 / LOW ≥0.40` (near-zero point → never HIGH). 📐 empirical residual quantiles noted as a refinement. |
| 4 | Batch trigger | 🔨 Dedicated **web-less bootable** `BatchApplication` (`spring.main.web-application-type=none`) + `ForecasterJob implements ApplicationRunner`, runs once and exits via `make forecast`. Keeps ML out of the read JVM (ADR-0001). 📐 AWS: same image as a scheduled **Fargate** task fired by **EventBridge** cron. *(Rejected: `@Scheduled` inside the api app — shared failure domain; bare `main` — loses DI/datasource/Flyway.)* |
| 5 | Horizon ↔ window | 🔨 Horizons `{7,30,365}` are trailing-window **sums**; a serving `window` row holds the predicted **next-window total** — identical units to Phase-2 actuals, so the UI `mode` toggle compares like-for-like and `delta_vs_prior` is "next window vs last window". Lift the `{7,30,365}` map to a shared `WindowDays` util in `topsales-common` so batch/actuals/eval agree on one definition. |
| 6 | Rollup + rank | 🔨 Forecast per `(category, channel)` → **roll up to `all` before ranking** (`all.point = Σ channels`; intervals = Σ bounds — simple, conservative; confidence = **min** of contributing channels). Then rank each partition `pk = tenant#forecast#window#channel` (channel ∈ online/offline/**all**) **independently**, desc by value, tie-break `categoryId` asc, `K_max=50`. *(Rank-after-rollup: a category can be #3 online, #1 offline, yet #1 in `all` — only summing then ranking is correct.)* 📐 quadrature intervals noted. |
| 7 | Serving writer | 🔨 `ServingTableRepository` port (already named in LLD: `readActive` + `writeVersionAndSwap`), impl `JdbcServingTableRepository` in **`topsales-ingestion`** — the shared data-plane both `topsales-forecast` (write) and `topsales-api` (Phase-4 read) reach **without** a `api→forecast` cycle. Per-`pk` `@Transactional`: insert rows at `version = max(version)+1` → single-row upsert flip of `serving_active_version` (the **atomic swap**; enables last-good + rollback) → prune to last `K=3`. Shared `ServingKey.of(tenant,window,mode,channel)` builder guards writer/reader pk drift. |
| 8 | `delta_vs_prior` | 🔨 `(forecast − prior trailing-window actual)/prior`, computed **in-memory** from the already-loaded history (no extra query); `null` when prior = 0. User-facing ("+12% vs last month"). |
| 9 | Eval method | 🔨 **Expanding-window** time-series CV (no shuffle — every test point strictly later than its training): initialTrain 84d (≥2 monthly cycles for HW), test horizon 7d, step 7, ~12 folds, week-anchored; drop a short trailing fold. **WAPE = Σ\|a−f\|/Σ\|a\|**, **bias = Σ(f−a)/Σ\|a\|**, **pooled** (ratio-of-sums, volume-weighted — *not* mean-of-WAPEs) at series / forecaster / overall grains. Gaps align as real **zeros** (penalizes forecasting sales on a no-sale day). `Σ\|a\|==0` → `defined=false`, excluded from the rollup. Reads the committed seed **directly** (pure JVM, no DB → runs on this host). |
| 10 | Eval output | 🔨 `make eval` (exec-plugin `EvalMain`) regenerates a committed `docs/forecast-eval-report.md` + `data/eval/forecast-eval.csv`: per-segment table, **naive-vs-HW side-by-side** (the ADR-0005 baseline-beat story — HW wins on dense/seasonal, both degrade on sparse/outlier), overall pooled rollup; deterministic (fixed sort, pinned rounding, `Locale.ROOT`). Plus `BacktestRegressionTest` (pure JVM, in `make test`): HW beats naive on dense, dense WAPE < threshold, sparse WAPE elevated. 📐 "+metrics": leave a Micrometer seam on `SegmentMetrics` (gauge `forecast.wape{…}`), wired in Phase 6 — don't add the dependency now. |

## Steps (as planned)
Layered so each compiles on the one before; seam additions first.

1. **`topsales-common` seam additions.** New `forecast/ServingTableRepository.java`,
   `forecast/ServingKey.java`, a `WindowDays` util (or onto `Window`) — **lift the `{7,30,365}` map
   out of `ActualsService`** (it still holds a private copy). Extend
   `repository/AggregateRepository.java` with `allSeries(tenantId, from, to)` (channel-aware,
   ordered `category_id, channel, bucket_date` for grouping). **✅ Already present (Phase 2.5):
   `AggregateRow.channel` and `TenantConfigRepository.allTenantIds()` — reuse, don't re-add.**
   *Verify:* `mvn -pl topsales-common test` green.
2. **JDBC impls (`topsales-ingestion`).** `JdbcServingTableRepository` (atomic-swap transaction,
   prune to K=3) + `allSeries` (`allTenantIds` impl already exists). *Verify:* `JdbcServingTableRepositoryIT` — write v1 →
   active=1; write again → v2, active flips, both versions retained, `readActive` returns v2, prune
   keeps last K (CI-only).
3. **Forecaster math (`topsales-forecast`).** `math/SeriesPrep` (grid regularization, gap-fill,
   `asOf`, density) → `model/SeasonalNaiveForecaster` → `math/ResidualIntervals` + `ConfidenceMapper`
   → `model/HoltWintersForecaster` → `model/TrendOnly`/`SparseRate`/`FlatPrior` →
   `ColdStartForecaster` dispatcher → `config/ForecastWiring` exposing **one** `Forecaster` bean
   (POJOs, not `@Component`; no `@Profile` on the baseline). 📐 `package-info`/javadoc stubs for
   `CrostonForecaster` and the SageMaker drop-in (ADR-0004/0005). *Verify:* unit tests per model
   (deterministic series → known forecast; gap + signed-returns; additive handles zero/negative
   without NaN) + dispatcher branch-coverage (length 0, <m, m..2m, ≥2m, intermittent;
   sparse-before-length); interval growth + confidence thresholds.
4. **Batch runner (`topsales-forecast`).** `pom.xml`: add `topsales-ingestion` dep, starter-jdbc,
   postgresql, `spring-boot-maven-plugin`. `BatchApplication` (web-less, `scanBasePackages=
   "com.topsales"`), `ForecasterJob`: load tenants → per tenant load `allSeries` over
   `history-days` (730) → group by `(category, channel)` → `forecaster.forecast(...)` → **roll up to
   `all`** → **rank per pk** → `writeVersionAndSwap`. Add `make forecast`. *Verify:* `ForecasterJobTest`
   with fakes asserts **9 pk writes** (3 windows × 3 channels), contiguous ranks, **`all == Σ
   channels`**, `delta_vs_prior`, cold-start → LOW. Optional CI-only `ForecasterJobIT` end-to-end.
5. **Eval harness (`topsales-forecast/eval/`).** `FoldSplitter`, `SeriesKey`, `ActualSeries`
   (zero-fill), `WapeCalculator` + `SegmentMetrics` (BigDecimal sums, near-zero guard),
   `BacktestRunner`, `EvalReport` (console + markdown + CSV), `EvalMain`. Add `exec-maven-plugin` +
   `make eval`; generate `docs/forecast-eval-report.md`. `BacktestRegressionTest` as the always-on
   guard. *Verify:* `make eval` rewrites the report byte-identically twice; `WapeCalculatorTest`
   hand-computed (`{100,200,300}` vs `{110,180,330}` → WAPE 0.10); `FoldSplitterTest` (168d → 12
   folds, expanding); regression test passes on the seed.

## Acceptance checklist  — Phase 3 done when all true
- [ ] `make forecast` runs the batch to completion and exits; `serving_rows` + `serving_active_version`
      populated for every `(window × channel)` pk per tenant.
- [ ] Forecasts carry point value, interval (low/high), confidence, and cold-start (via LOW).
- [ ] `all` serving rows = the exact **sum of channel-grain** point values; each pk independently ranked.
- [ ] Versioning is atomic (a rerun = a new version + pointer flip; prior versions retained for rollback).
- [ ] `make eval` produces a deterministic **WAPE + bias report** on the seed (per-segment +
      naive-vs-HW + overall); HW beats seasonal-naive on dense series; sparse/outlier WAPE is elevated.
- [ ] `make test` green (incl. `BacktestRegressionTest`); `*IT` written (CI).

## Out of scope / deferred
- `ForecastProvider`/`PrecomputedForecastProvider` read wiring, the full degradation chain, Redis
  caching, and the forecast UI (mode toggle + forecast-vs-actual chart + status badge) — **Phase 4**.
  *(Phase 4 will depend `topsales-api → topsales-forecast` to reuse `SeasonalNaiveForecaster` for the
  fallback, and on the shared `ServingTableRepository`/`ServingKey` — keep `SeasonalNaive`
  common-deps-only to make that cheap.)*
- 📐 Micrometer/WAPE gauges — seam left on `SegmentMetrics`, wired in Phase 6.
- 📐 Python/SageMaker global model + Croston for intermittent demand — behind the same `Forecaster`
  seam, promoted per-segment when accuracy data justifies it (ADR-0004/0005); **not coded**.
- 📐 Multiplicative Holt-Winters — `Seasonality` enum hook only.

## Cross-phase note
`AggregateRow` gained its `channel` field in **Phase 2.5** (✅ done); the forecaster math and eval are
channel-agnostic (they receive a single pre-filtered series) — only the **batch** groups by channel.

**Refreshed after Phase 2.5 (2026-06):** Phase 2.5 delivered some of this phase's Step-1 groundwork —
`TenantConfigRepository.allTenantIds()` and `AggregateRow.channel` now exist; `TopKResponse` already
echoes `channel` and carries `windowFrom`/`windowTo`. None of this changes Phase 3 (backend
forecasting); it's pure reuse. Forward note for **Phase 4**: the forecast read path should populate
`windowFrom`/`windowTo` with the forward-looking forecast-horizon window (the field semantics already
support it). Everything else in this plan (serving writer, forecaster math, batch, WAPE eval) stands
unchanged and is execution-ready.
So Phase 2.5 must merge first, but Phase-3 forecaster/eval work can proceed against the seam in parallel.
