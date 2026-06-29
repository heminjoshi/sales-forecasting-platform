# Low-Level Design — Multi-Tenant Sales-Forecasting Platform

**Status:** v1 · **Derives from** `docs/hld.md` (Design Doc v3) and `docs/component-deep-dive.md`.
**Scope:** the **local-runnable built system** (Java 21 + Spring Boot, Postgres, Redis), with the
cloud impl noted per seam. This document is the **implementation contract**: an engineer should be
able to build the API + UI from it without the design doc.

**Conventions.** Profiles: `local` (built, default) vs `aws` (designed). Money: `numeric(18,2)`,
minor-unit-safe. Time: `timestamptz` (UTC) on the wire; **bucketing is tenant-local** (A6). IDs are
opaque strings. Enums are lowercase on the wire unless noted.

## Contents
1. Module map · 2. Data model & DDL · 3. Public API contract · 4. Interface seams ·
5. Read pipeline & degradation chain · 6. Ingestion & idempotency · 7. Cache design ·
8. Forecast batch · 9. Insight generation · 10. Profile wiring · 11. Tenant scoping & security ·
12. Sizing math · 13. UI contract · 14. Error model

---

## 1. Module map (Maven multi-module)

| Module | Owns | Key types |
|---|---|---|
| `topsales-common` | domain model, DTOs, the seam interfaces, config | `SaleEvent`, `AggregateRow`, `ForecastValue`, `ServingRow`, `TopKResponse`, `Forecaster`, `ForecastProvider`, `InsightGenerator`, repository ports |
| `topsales-ingestion` | `POST /events`, dedupe, additive upsert, raw-log append | `IngestionController`, `IngestionService`, `EventLedger` |
| `topsales-forecast` | batch runner, baseline forecasters, eval | `SeasonalNaiveForecaster`, `HoltWintersForecaster`, `ForecasterJob`, `WapeEvaluator` |
| `topsales-insight` | `InsightGenerator` impls | `TemplateInsightGenerator`, `BedrockInsightGenerator` |
| `topsales-api` | read controllers, pipeline, cache, degradation, tenant filter, static UI, actuator | `TopCategoriesController`, `ReadPipeline`, `CacheShell`, `DegradationChain`, `TenantScopeFilter` |
| `db/migration` | Flyway SQL | `V1__events.sql` … `V4__tenant_config.sql` |

The seam interfaces and DTOs live in `topsales-common` so every other module depends only on the
contract, never on a concrete impl.

---

## 2. Data model & DDL (Postgres, `local` profile)

Four tables. The **filesystem raw log** (newline-delimited JSON under a configured dir) is the
immutable replay source-of-truth — the local stand-in for the S3 raw log; the `events` table is the
durable **dedupe ledger**.

```sql
-- V4__tenant_config.sql  (load before ingesting; supplies tz + currency, A6)
CREATE TABLE tenant_config (
  tenant_id          text PRIMARY KEY,
  timezone           text        NOT NULL,         -- IANA, e.g. 'America/Los_Angeles'
  reporting_currency text        NOT NULL          -- ISO 4217, e.g. 'USD'
);

-- V1__events.sql  (durable event ledger + idempotency gate)
CREATE TABLE events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       text         NOT NULL,
  order_id        text         NOT NULL,
  category_id     text         NOT NULL,
  amount          numeric(18,2) NOT NULL,          -- signed: RETURN/ADJUSTMENT are negative
  currency        text         NOT NULL,
  event_type      text         NOT NULL CHECK (event_type IN ('SALE','RETURN','ADJUSTMENT')),
  event_time      timestamptz  NOT NULL,
  bucket_date     date         NOT NULL,           -- tenant-local day of event_time
  idempotency_key text         NOT NULL,
  received_at     timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT uq_events_idem UNIQUE (idempotency_key) -- the dedupe gate
);
CREATE INDEX ix_events_tenant_bucket ON events (tenant_id, bucket_date);

-- V2__aggregates.sql  (authoritative rollup; PK = (tenant, category, day))
CREATE TABLE aggregates (
  tenant_id    text          NOT NULL,
  category_id  text          NOT NULL,
  bucket_date  date          NOT NULL,
  sum_amount   numeric(18,2) NOT NULL DEFAULT 0,
  order_count  integer       NOT NULL DEFAULT 0,
  currency     text          NOT NULL,
  updated_at   timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, category_id, bucket_date)
);
CREATE INDEX ix_agg_tenant_cat_date ON aggregates (tenant_id, category_id, bucket_date);

-- V3__serving.sql  (precomputed top-k; versioned for atomic swap + rollback)
CREATE TABLE serving_rows (
  pk             text          NOT NULL,           -- 'tenant#window#mode'
  version        integer       NOT NULL,
  rank           integer       NOT NULL,           -- 1..k
  category_id    text          NOT NULL,
  value          numeric(18,2) NOT NULL,
  interval_low   numeric(18,2),                    -- null in actuals/degraded
  interval_high  numeric(18,2),
  confidence     text          CHECK (confidence IN ('HIGH','MEDIUM','LOW')),
  delta_vs_prior numeric(8,4),                     -- e.g. 0.1200 = +12%
  as_of          timestamptz   NOT NULL,
  PRIMARY KEY (pk, version, rank)
);

CREATE TABLE serving_active_version (
  pk             text PRIMARY KEY,                 -- 'tenant#window#mode'
  active_version integer NOT NULL,
  as_of          timestamptz NOT NULL
);
```

**Atomic swap.** The batch writes all `k` rows of a new `version` into `serving_rows`, then a single
`UPDATE serving_active_version SET active_version = :v` flips reads onto it. **Rollback** = point the
pointer at a prior version (rows are retained; prune old versions on a lifecycle job). A read is:

```sql
SELECT r.* FROM serving_rows r
JOIN serving_active_version a ON a.pk = r.pk AND a.active_version = r.version
WHERE r.pk = :pk ORDER BY r.rank;   -- one keyed scan = the whole top-k
```

**Cloud mapping (`aws`):** `aggregates` → Aurora Postgres; `serving_rows` → DynamoDB
(`pk=tenant#window#mode`, `sk=zero-padded rank`, item carries `version`/`as_of`); `events` raw log → S3.
Same logical shapes; see `docs/hld.md` §19.

---

## 3. Public API contract

Base path `/api/v1`. Full schema in `docs/api/openapi.yaml` (Stage 2). Tenant is derived from the
authenticated context, **never** from the body (A1, §11).

### 3.1 Ingestion — `POST /api/v1/events`
Local stand-in for the Kinesis path. Accepts one event or a batch; idempotent.

```jsonc
// request: SaleEvent (or array of)
{ "tenantId":"t_123","orderId":"o_998","categoryId":"cat_office","amount":42.50,
  "currency":"USD","eventType":"SALE","eventTime":"2026-06-20T14:03:00Z",
  "idempotencyKey":"o_998:SALE" }
```
- `202 Accepted` → `{ "received": N, "applied": M, "deduped": N-M }`.
- Malformed events are not retried into the pipeline — they go to the local quarantine table
  (`aws`: SQS DLQ) and are counted: `{ "received":N, "applied":M, "deduped":D, "quarantined":Q }`.
- `idempotencyKey` defaults to `orderId:eventType` if omitted.

### 3.2 Read — `GET /api/v1/tenants/{tenantId}/top-categories`
Query params: `mode=forecast|actuals` (default `forecast`), `window=week|month|year`
(default `month`; `quarter` reserved), `k=1..50` (default `10`).

Returns `200` + `TopKResponse` (§13). Always returns a body with a `status` — reads never fail closed
(§5). `404` only if the tenant is unknown; `403` if the path tenant ≠ authenticated tenant.

### 3.3 Ops
- `GET /actuator/health` — liveness/readiness (used by ALB health checks in `aws`).
- `GET /actuator/prometheus` — Micrometer scrape (Phase 6).

---

## 4. Interface seams (the swap points)

All in `topsales-common`. Each has a built impl (`local`) and a designed impl (`aws`); selecting
between them is a profile/config decision, not a code change (`docs/hld.md` §14).

```java
// Pluggable forecasting model. Pure function of history → values; no I/O.
public interface Forecaster {
    List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx);
}
// ctx: tenantId, categoryId, int[] horizons, Window window
// ForecastValue: horizon, pointValue, intervalLow, intervalHigh, Confidence

// Versioned serving-table reader. The ONLY coupling between forecast and read planes.
public interface ForecastProvider {
    Optional<ServingResult> getTopK(TopKQuery query);   // (tenantId, window, mode, k)
}
// ServingResult: List<ServingRow> rows, int version, Instant asOf
// built: PrecomputedForecastProvider (reads serving_rows by active version)
// designed: OnDemandForecastProvider / MergingForecastProvider (A→B→C, hld §14)

// Bounded, grounded NL insight. Verbalizes ONLY the provided numbers.
public interface InsightGenerator {
    String generate(InsightRequest req);   // top-k items (numbers only), mode, window
}
// built: TemplateInsightGenerator   designed: BedrockInsightGenerator

// Repository ports (built: JdbcTemplate/Postgres; designed: Aurora/DynamoDB/S3)
public interface AggregateRepository {
    int upsertAdditive(AggregateDelta d);                       // ON CONFLICT DO UPDATE +=
    List<AggregateRow> rangeByCategory(String tenantId, LocalDate from, LocalDate to);
}
public interface ServingTableRepository {
    Optional<ServingResult> readActive(String pk);
    void writeVersionAndSwap(String pk, int version, List<ServingRow> rows, Instant asOf);
}
public interface EventLedger {                                   // raw log + dedupe gate
    boolean record(SaleEvent e);   // returns true if newly inserted (not a duplicate)
}
public interface TenantConfigRepository {
    TenantConfig get(String tenantId);                          // timezone, reportingCurrency
}
```

**Why these seams (one line each, full rationale in the ADRs):** `Forecaster` lets baseline↔ML swap
(DR-4); `ForecastProvider` lets precompute↔on-demand↔hybrid swap without touching the read path
(DR-1, §14); `InsightGenerator` lets template↔Bedrock swap and guarantees a deterministic floor
(DR-6); the repos let local↔cloud stores swap (DR-2/DR-3).

---

## 5. Read pipeline & degradation chain

`ReadPipeline.handle(query)` runs the 7 steps from the deep-dive (§B2). Steps 1–3, 6–7 are mode-
agnostic; step 4/5 differ by mode.

1. **Tenant scope** — `TenantScopeFilter` derives tenant from auth; assert `path == authed` (§11).
2. **Cache** — `CacheShell.get(key)`; hit → return; miss → single-flight lease, continue (§7).
3. **Mode routing** — `actuals` → `ActualsService`; `forecast` → step 4.
4. **Forecast read** — `ForecastProvider.getTopK`. Present & within freshness SLO → `fresh`.
5. **Degradation chain** — only on the forecast path when rows are missing/stale:

   | Order | Source | `status` | Intervals | Trigger |
   |---|---|---|---|---|
   | 1 | active serving rows, fresh | `fresh` | yes | as-of within freshness SLO |
   | 2 | active serving rows, old | `stale` | yes | last-good beyond SLO but present |
   | 3 | JVM seasonal-naive from `aggregates` | `degraded` | omitted/uncertain | serving rows absent/unreadable |
   | 4 | actuals top-k from `aggregates` | `pending` | none | no forecast version exists yet |

   The chain never throws: step 4 (`pending`) is pure aggregation and is the always-available floor.
   `actuals` mode bypasses the chain — it is always `fresh` from aggregates.
6. **Insight** — `InsightGenerator.generate` (lazy; Bedrock with timeout → template; cached, §9).
7. **Assemble** — rank, `deltaVsPrior`, `confidence`, `status`, `asOf`; cache; return.

**Freshness SLO** (drives `fresh` vs `stale`): `now - asOf <= forecast.freshness-slo` (default `36h`
for a daily cadence). Configurable per `application.yml`.

---

## 6. Ingestion & idempotency

`IngestionService.ingest(event)` per event, inside one transaction:

1. **Validate** → on failure, write to `quarantine` (local) / DLQ (`aws`); count, do not retry.
2. **Resolve tenant config** (`TenantConfigRepository`) → `timezone`, `reportingCurrency`.
3. **Bucket** → `bucket_date = event_time` converted to the tenant's local day (A6).
4. **Dedupe gate** → `EventLedger.record(event)`:
   `INSERT INTO events (...) ON CONFLICT (idempotency_key) DO NOTHING`. Also append the raw event to
   the filesystem raw log. Returns `false` if it was a duplicate → **skip steps 5** (counts as `deduped`).
5. **Additive upsert** (only for newly-recorded events):
   ```sql
   INSERT INTO aggregates (tenant_id,category_id,bucket_date,sum_amount,order_count,currency)
   VALUES (:t,:c,:d,:amt,1,:cur)
   ON CONFLICT (tenant_id,category_id,bucket_date)
   DO UPDATE SET sum_amount = aggregates.sum_amount + EXCLUDED.sum_amount,
                 order_count = aggregates.order_count + 1,
                 updated_at  = now();
   ```

**Exactly-once *effect*:** at-least-once delivery (replay/redelivery) is safe because the unique
`idempotency_key` gate ensures each event increments the aggregate **once**. Additive math makes the
result independent of arrival order/lateness — each day-bucket is computed independently.
`RETURN`/`ADJUSTMENT` carry signed amounts, so they net correctly.

**Replay/recovery:** truncate `aggregates`, clear `events`, re-feed the raw log → identical aggregates
(idempotent). The raw log is the only precious local data (parity with S3 SoT).

---

## 7. Cache design (`CacheShell`, Redis)

- **Key:** `topk:{tenantId}:{ver}:{window}:{mode}:{k}` where `{ver}` is the **per-tenant cache
  version** integer held at `tenantver:{tenantId}`.
- **Value:** serialized `TopKResponse` (JSON).
- **TTL:** base TTL (default `15m`) **+ random jitter** (±20%) to avoid lock-step expiry stampedes.
- **Event-driven invalidation:** the forecast batch (and any aggregate-affecting admin op) **bumps**
  `tenantver:{tenantId}` (`INCR`). Old keys embed the old `{ver}` and are simply never read again
  (they expire out). This is O(1) invalidation without key scanning.
- **Single-flight:** on a miss, acquire a short Redis lease (`SET key:lock NX PX 2s`); the leader
  computes and populates, followers briefly poll/await — prevents cache stampede on hot tenants.
- **Failure:** Redis down → fall through to the Serving Table / DB (correct, slower). The cache is an
  accelerator, never a correctness dependency.

---

## 8. Forecast batch (`ForecasterJob`)

Triggered by `make forecast` locally (EventBridge cron in `aws`). Per `(tenant, category)`:

1. `AggregateRepository.rangeByCategory` → history (default trailing 730 days).
2. Pick a `Forecaster`: `HoltWintersForecaster` when ≥1 full season of history; else
   `SeasonalNaiveForecaster`; **cold-start** (<1 season → trend-only; none → prior/flat, `LOW`
   confidence). Sparse-series Croston is **designed, not built** (noted in ADR-0005).
3. Produce point + interval + `confidence` per horizon; aggregate to the window; **rank** to top-k per
   `(window, mode=forecast)`; compute `delta_vs_prior`.
4. `ServingTableRepository.writeVersionAndSwap(pk, newVersion, rows, asOf)` — write then flip pointer.
5. Emit predicted-vs-actual to the evaluator. **Eval = WAPE + bias** (DR-5), via time-series-CV
   backtest; report written for the demo.

Partitioned by tenant (embarrassingly parallel). On partial failure the prior `active_version` stays
live — the read-path degradation chain covers any gap.

---

## 9. Insight generation

`InsightRequest` carries **only computed figures** (category labels + value + delta), never raw user
free-text beyond the category name, which is treated as untrusted (prompt-injection, §11).

- **Built (`TemplateInsightGenerator`):** deterministic sentence from the top items, e.g.
  *"{top} leads this {window} (~{+delta%}); {second} follows."* No model, no network.
- **Designed (`BedrockInsightGenerator`):** `InvokeModel` (AWS SDK for Java), small model id from
  config; **output validated** to contain only the provided numbers; **timeout (2s) / circuit breaker
  → template**; guardrail (prod). Lazy (first view) + cached with the response (§7).

The response **always** carries an insight — the template is the floor; Bedrock never blocks.

---

## 10. Profile wiring

A single Spring profile selects every impl behind the seams. No cloud type is referenced on the
common path.

```yaml
# application.yml (defaults: local)
spring:
  profiles:
    active: local
topsales:
  forecast:
    freshness-slo: 36h
    history-days: 730
  cache:
    base-ttl: 15m
    jitter-pct: 20
  rawlog:
    dir: ./data/rawlog       # local S3 stand-in
```

```java
@Configuration
class LocalWiring {                         // @Profile("local")
  @Bean ForecastProvider forecastProvider(ServingTableRepository r) { return new PrecomputedForecastProvider(r); }
  @Bean InsightGenerator insightGenerator() { return new TemplateInsightGenerator(); }
  @Bean AggregateRepository aggregateRepository(JdbcTemplate t) { return new JdbcAggregateRepository(t); }
}
@Configuration
class AwsWiring {                           // @Profile("aws")  — designed
  @Bean InsightGenerator insightGenerator(BedrockClient c) { return new BedrockInsightGenerator(c); }
  // DynamoForecastProvider, S3EventLedger, …  (same interfaces)
}
```

The controller, DTOs, tenant scoping, cache shell, actuals path, and UI are **identical** across
profiles — the entire local↔cloud difference is bean selection (`docs/hld.md` §14, §19).

---

## 11. Tenant scoping & security

- `TenantScopeFilter` (servlet filter, ordered first) derives `tenantId` from the authenticated
  context (A1). Locally a dev header `X-Tenant-Id` stands in for upstream auth.
- Every repository method takes `tenantId` as the first key column; **no query trusts a body/path
  tenant id** — the path `{tenantId}` is asserted equal to the authenticated tenant or `403`.
- **Prompt-injection:** category names are untrusted; passed to the LLM as delimited data, output
  validated to the provided numbers, guardrail in prod, model has no tools/write (§9, hld §17).
- **CORS:** allow-list the UI origin only — `localhost` (local) + the Vercel domain (prod). S3+
  CloudFront would be same-origin and skip CORS (the documented trade-off, DR-8).
- Encryption in transit/at rest, least-privilege IAM, PII minimization — `aws` profile / CDK (Phase 7).

---

## 12. Sizing math (capacity sanity check)

- ~1M tenants × ~100 orders/day ≈ **100M events/day** (~1–2K eps avg, ~10–20K peak) — absorbed by
  the stream buffer (`aws`) / batched `POST` (local).
- Forecast fan-out ≈ 1M × ~50 categories × 3 horizons ≈ **~150M series-forecasts/day**, parallel by
  tenant; the **batch refresh window** is the first scaling limit → incremental refit + tiered
  cadence (hld §15).
- Serving table ≈ tens of millions of small rows; reads are O(top-k) point lookups, Redis-fronted →
  read path scales flat. p99 target < ~150 ms (NFR3).

---

## 13. UI contract

The dashboard is a thin, read-only view over the API (no business logic). Response it renders:

```jsonc
// TopKResponse  (GET .../top-categories)
{ "tenantId":"t_123","mode":"forecast","window":"month","k":10,
  "status":"fresh|stale|pending|degraded","asOf":"2026-06-28T06:00:00Z",
  "insight":"Office Supplies leads this month (~+12%); Electronics follows.",
  "items":[ { "rank":1,"category":"Office Supplies","value":5400.00,"deltaVsPrior":0.12,
              "confidence":"HIGH","interval":{"low":4900,"high":5900} } ] }
```

- **Controls:** `mode` (forecast/actuals toggle), `window` (week/month/year), `k`.
- **Renders:** ranked table (rank, category, value, Δ vs prior, confidence), forecast-vs-actual chart
  (Chart.js CDN, demo), the `insight` line, and a **status badge** `fresh|stale|pending|degraded` +
  the `asOf` timestamp.
- **States:** explicit loading / empty / error / **degraded** — the UI must always render something
  honest; `interval`/`confidence` are absent in `actuals`/`pending` and the UI hides those columns.

---

## 14. Error model

Errors are `application/problem+json` (RFC 7807):

```jsonc
{ "type":"https://topsales/errors/tenant-mismatch","title":"Tenant mismatch",
  "status":403,"detail":"Path tenant does not match the authenticated tenant.",
  "instance":"/api/v1/tenants/t_999/top-categories" }
```

| Status | When |
|---|---|
| `202` | events accepted (with applied/deduped/quarantined counts) |
| `400` | malformed request (bad enum, k out of range) |
| `403` | path tenant ≠ authenticated tenant |
| `404` | unknown tenant |
| `200` + `status` flag | **read degraded** — never a 5xx for a degraded forecast; the body's `status` tells the truth |
| `5xx` | only genuine infra failure (DB + cache + actuals all unavailable) |
```
