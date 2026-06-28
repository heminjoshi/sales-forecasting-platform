# Component Deep-Dive — Architecture Boxes, Connections & Data Flow

> One section per box: **what it does, what flows in/out, internals, tech (cloud + local), failure, scale.** Then the **edge catalog** (every connection + payload), the **end-to-end flows**, and the **data shapes** on the wire. This is the 20-min Component Deep Dive and the LLD component section.

**How to read the diagram**
- **Planes** = lifecycle/responsibility boundaries (Presentation / Ingestion / Forecast / Serving), not network boundaries.
- **Solid edge** = primary synchronous/async path always taken. **Dotted edge** = conditional/fallback (lazy insight, ML-down degradation, malformed→DLQ).
- **Coupling rule:** the Forecast and Serving planes touch only through the **Serving Table** (data plane) — no synchronous ML call on the read path.

---

## A. Presentation Tier

### A1 · Dashboard UI
**Responsibility:** read-only view of a tenant's top categories; lets the seller pick **mode** (forecast/actuals), **window** (week/month/year), **k**; renders a ranked table, a forecast-vs-actual chart, the NL insight, and a freshness/status badge.
**In:** user interactions (control changes). **Out:** `GET /tenants/{id}/top-categories?mode&window&k` to the API.
**Internals:** on control change → fetch → render table + Chart.js chart + insight + status badge; explicit loading / empty / error / degraded states (it must render *something honest* even when data is degraded).
**Tech:** *demo* = static HTML + vanilla JS + Chart.js (CDN), served from Spring Boot static resources (no Node toolchain); *prod* = React SPA (Vite + Recharts) on **Vercel** (S3+CloudFront = AWS-native alternative).
**Fails:** API error → friendly message; degraded/pending forecast → badge says so; never crashes on partial data.
**Scales:** static assets; CDN-served in prod; small per-tenant concurrency (A9).

---

## B. Serving Plane

### B1 · API Gateway / ALB
**Responsibility:** single entry point — TLS termination, routing, health-aware load distribution; (API Gateway, optionally in front) request validation, per-tenant **rate limiting** (token bucket), usage plans, CORS.
**In:** HTTPS from the UI/clients. **Out:** HTTP to a healthy Spring Boot task.
**Internals:** ALB target group health checks (`/actuator/health`) pull unhealthy tasks; **CORS allow-list** carries the Vercel origin (cross-origin) or `localhost` locally; 429 on throttle, 503 if no healthy targets.
**Tech:** ALB for ECS Fargate; optional API Gateway for throttling/keys. Local: the service is hit directly on `localhost:8080`.
**Fails / Scales:** managed; autoscales; removes unhealthy targets automatically.

### B2 · Spring Boot Service (stateless) — the orchestrator
**Responsibility:** assemble the top-k response. This box does the most: tenant scoping → cache lookup → mode routing → read precomputed forecasts **or** aggregate actuals → run the degradation chain → attach the insight → surface status/as-of.
**In:** HTTP `(tenantId, mode, window, k)`. **Out:** JSON response; R/W Redis; read Serving Table; read Aurora; call Bedrock; invoke the in-process JVM fallback.
**Internals (the pipeline):**
1. `TenantScopeFilter` — derive tenant from the authenticated context; enforce on every query (never trust a body-supplied id).
2. `CacheShell` — key `(tenant,window,mode,k)`; hit → return; miss → continue + populate (single-flight).
3. Mode routing — *actuals* → `ActualsService` (aggregate query on Aurora); *forecast* → `ForecastProvider`.
4. `ForecastProvider` (forecast mode) → `PrecomputedForecastProvider` reads the Serving Table.
5. **Degradation chain** (forecast, when rows missing/stale): last-good → **JVM seasonal-naive** from actuals → actuals with `forecast_pending`.
6. `InsightGenerator` — lazy-generate via Bedrock + cache, or template fallback.
7. Assemble — rank, delta vs prior period, confidence, `status`, `as-of`.
**Tech:** Spring Boot on Fargate, stateless, autoscaled (JVM concurrency, low-latency reads, mature ecosystem). Same binary locally; **Spring profile** swaps the impls behind each interface.
**Fails:** every downstream has a fallback (cache→DB, serving-table-miss→degrade, Bedrock→template); stateless, so any instance serves any request.
**Scales:** horizontal autoscale on CPU/RPS; Redis absorbs hot tenants; reads are O(top-k).

### B3 · Redis cache
**Responsibility:** cache the assembled top-k response; cut Serving-Table/Aurora reads; absorb hot tenants.
**In/Out:** `get/set` from the service. **Internals:** key `(tenant,window,mode,k)`, value = serialized response; **jittered TTL** (avoid lock-step expiry); **event-driven invalidation** — the batch bumps a per-tenant version embedded in the key, so a stale entry can't outlive a refresh; single-flight on miss (stampede protection).
**Tech:** ElastiCache Redis (cloud) / Redis container (local).
**Fails:** Redis down → fall through to the Serving Table/DB (slower, correct). **Scales:** replication group; cluster mode if ever needed.

### B4 · Bedrock (grounded insight)  *(dotted: lazy, cached)*
**Responsibility:** turn the computed top-k numbers into a one-line NL insight, **grounded** in those numbers.
**In:** a prompt = system instructions + the *provided* figures (categories, amounts, deltas). **Out:** validated insight text.
**Internals:** `InvokeModel` via AWS SDK for Java; small/fast model (config from SSM); **guardrail** applied (prod); the model **only verbalizes provided values** — output validated to contain only those figures; **deterministic template** fallback; **lazy** (first view) + **cached**.
**Tech:** Bedrock behind `InsightGenerator` (cloud) / `TemplateInsightGenerator` (local).
**Fails:** timeout / circuit breaker → template; never blocks the response.
**Scales:** cost bounded — only top-k, only viewed tenants, cheap model, cached.

### B5 · JVM baseline fallback  *(dotted: ML plane down)*
**Responsibility:** compute a forecast **in-process** from actuals when precomputed forecasts are missing/stale — keeps the read path alive with the entire ML plane down.
**In:** recent aggregates from Aurora. **Out:** degraded top-k (status flagged).
**Internals:** pure arithmetic (seasonal-naive / moving average / last-period) — **no Python, no ML**; runs in the service JVM; marks `status=degraded` / `forecast_pending`.
**Tech:** Java in the service. **Fails:** it *is* the fallback; if even actuals are unavailable → last cache / replica, else (rare) error. **Scales:** cheap, in-process.

---

## C. Ingestion Plane

### C1 · Upstream sale/return events
**Responsibility:** the source — emits sale / return / adjustment events; owned upstream (A2), not built here.
**Out:** events to Kinesis. **Shape:** `{tenantId, orderId, categoryId, amount, currency, eventType, eventTime, idempotencyKey}` (returns/cancellations are signed/negative `eventType`).

### C2 · Kinesis / MSK
**Responsibility:** durable, partition-ordered **ingestion buffer**; decouples producers from consumers; absorbs bursts; enables replay.
**In:** events from upstream. **Out:** to the consumer.
**Internals:** partition by `tenantId` (per-tenant ordering); retention window enables reprocessing; bursts buffer here so consumers can lag and catch up (backpressure handled by the queue).
**Tech:** Kinesis on-demand / MSK (cloud); **local = `POST /events`** (no stream — the consumer's logic runs in a controller).
**Fails:** durable; consumer lag → scale consumers; retention → replay. **Scales:** shards / on-demand, partitioned by tenant.

### C3 · Ingestion Consumer
**Responsibility:** consume events → **idempotent-upsert** aggregates → **append** raw to S3 → route **malformed** to the DLQ.
**In:** events from the stream. **Out:** Aurora (upsert), S3 (append), DLQ (malformed).
**Internals:** **dedupe** on `idempotencyKey` (or `orderId+eventType`); bucket by **tenant-local day** (A6); **additive** sum/count update (so order/lateness don't matter); currency convert at ingest; validate → DLQ on failure; KCL **checkpointing** for resume.
**Tech:** Spring Boot + KCL (cloud) / the `POST /events` controller (local).
**Fails:** poison pill → DLQ + keep flowing; at-least-once delivery + idempotency = **exactly-once *effect***; checkpoint resumes after crash. **Scales:** scale consumers by shard/lag.

### C4 · Aurora Postgres — `(tenant, category, day)` rollups
**Responsibility:** the **authoritative aggregate store**; serves actuals reads and feeds the forecaster.
**In:** upserts from the consumer. **Out:** reads by the service (actuals + degradation) and the forecaster (history).
**Internals:** row = `(tenant_id, category_id, bucket_date) → sum_amount, order_count, currency`; indexes for range/group-by; **partition by tenant** at scale + read replicas; lives in isolated subnets.
**Tech:** Aurora Postgres Serverless v2 (cloud) / Postgres container (local). **Fails:** replica failover; if down → degrade to cache/last-good. **Scales:** partition + replicas.

### C5 · S3 Raw Event Log (SoT)
**Responsibility:** durable, **immutable source of truth**; the only precious data; enables replay/rebuild of everything downstream.
**In:** appends from the consumer. **Out:** replay → rebuild aggregates; (future) training data for the ML model.
**Internals:** partitioned by tenant/date; versioned; lifecycle to Infrequent-Access after 90 days.
**Tech:** S3 (cloud) / local filesystem dir (local). **Fails:** 11-nines durability; idempotent replay makes rebuild safe.

### C6 · DLQ  *(dotted: malformed)*
**Responsibility:** quarantine un-processable events; isolate poison pills so one bad event never stalls a partition; alert.
**In:** malformed events from the consumer. **Out:** alerting; reprocessing after a fix.
**Tech:** SQS (cloud) / a quarantine table (local).

---

## D. Forecast Plane (batch)

### D1 · EventBridge cron
**Responsibility:** trigger the batch forecaster on the **daily cadence** (A4).
**Out:** starts the Forecaster Job. **Tech:** EventBridge rule (cloud) / a `make`/scheduler trigger (local).

### D2 · Forecaster Job  (Java baseline / SageMaker future)
**Responsibility:** read aggregates → **forecast per `(tenant, category)` × horizon** → rank → write **versioned** serving rows + intervals → emit eval metrics.
**In:** aggregates from Aurora. **Out:** Serving Table (versioned write), Eval/Drift.
**Internals:** per-series fit (Holt-Winters level/trend/seasonal, or seasonal-naive); **cold-start** handling (<1 season → trend-only; none → prior/flat + low-confidence); prediction **intervals**/confidence; rank to top-k per `(window, mode)`; **partition by tenant** (embarrassingly parallel); incremental refit + tiered cadence at scale.
**Tech:** Java baseline on a scheduled Fargate task (**built**); **SageMaker batch transform (designed)** as a second `Forecaster` impl behind the same interface — drop-in, no serving-contract change.
**Fails:** partial failure → idempotent re-run; the Serving Table keeps last-good (degradation covers the gap). **Scales:** parallel by tenant; incremental + tiered cadence past the refresh-window limit.

### D3 · Serving Table — precomputed top-k + intervals
**Responsibility:** the **read-optimized store** the API serves forecasts from; pure point-lookup.
**In:** versioned writes from the forecaster. **Out:** reads by the service.
**Internals:** key `pk = tenant#window#mode`, `sk = zero-padded rank`; value = `{category, value, interval, confidence, version, as_of}`; **versioned** so a new batch is an atomic swap and rollback is a flip.
**Tech:** DynamoDB (cloud) / Postgres table (local), behind `ForecastProvider`. **Fails:** miss/stale → degradation chain; versioned rollback. **Scales:** tiny rows, point lookups, trivially cacheable.

### D4 · Eval / Drift (WAPE, bias)
**Responsibility:** measure accuracy/bias, detect drift, gate model promotion.
**In:** predicted-vs-actual (forecaster output + later aggregates). **Out:** metrics to CloudWatch; drift breach → refit/alert.
**Internals:** backtest **WAPE** + bias; rolling error per segment; threshold breach → trigger; **champion/challenger** gating before a new model is promoted.
**Tech:** part of the batch (Java), emitting Micrometer/CloudWatch metrics.

### D5 · CloudWatch / dashboards
**Responsibility:** observability sink — dashboards + alarms across **system** (RED/USE, stream lag, batch time, freshness SLO) and **ML-quality** (WAPE, bias, drift, cold-start %, insight faithfulness).
**In:** metrics from the service, consumer, forecaster, and Eval/Drift. **Out:** dashboards; alarms → SNS.
**Tech:** CloudWatch (cloud) / Micrometer + actuator (local).

---

## E. Connection & Data-Flow Catalog (every edge)

| # | From → To | Trigger | Payload / shape | Sync? | On failure |
|---|---|---|---|---|---|
| 1 | UI → API GW/ALB | user control change | GET + `(tenant via auth, mode, window, k)` | sync | UI shows error/degraded |
| 2 | API GW/ALB → Service | request routed | HTTP request, tenant-scoped | sync | 503/429; healthy-target routing |
| 3 | Service ↔ Redis | every read | key `(tenant,window,mode,k)` ↔ serialized response | sync | fall through to DB |
| 4 | Service → Serving Table | forecast mode, cache miss | read by `pk=tenant#window#mode` | sync | degradation chain |
| 5 | Service → Aurora | actuals mode / degradation | aggregate range-query `(tenant, window)` | sync | last cache / replica |
| 6 | Service ⇢ Bedrock | insight absent (lazy) | prompt w/ provided numbers → text | sync (bounded) | template fallback |
| 7 | Service → JVM fallback | forecast missing/stale | in-process; reads aggregates | sync | actuals + `forecast_pending` |
| 8 | Upstream → Kinesis | event emitted | `SaleEvent` (see shapes) | async | producer retries (upstream) |
| 9 | Kinesis → Consumer | poll/subscribe | `SaleEvent` batch | async | lag → scale; replay |
| 10 | Consumer → Aurora | per event | idempotent upsert to `(tenant,category,day)` | sync | retry; checkpoint resume |
| 11 | Consumer → S3 | per event | append raw `SaleEvent` | async | retry; durable |
| 12 | Consumer ⇢ DLQ | malformed | bad event + reason | async | alert; manual/auto reprocess |
| 13 | EventBridge → Forecaster | daily cron | trigger | async | next tick; idempotent re-run |
| 14 | Aurora → Forecaster | batch start | history per `(tenant, category)` | sync (batch) | retry segment |
| 15 | Forecaster → Serving Table | per series | versioned `ForecastRow` write | sync (batch) | keep last-good version |
| 16 | Forecaster → Eval/Drift | per batch | predicted vs actual | sync (batch) | metrics gap alarmed |
| 17 | Eval/Drift → CloudWatch | per batch | WAPE/bias/drift metrics | async | — |
| (cross-cut) | Service/Consumer → CloudWatch | continuous | RED + custom metrics | async | — |

---

## F. End-to-end flows

**F1 · Write path (ingestion).** Upstream emits `SaleEvent` → Kinesis (partition by tenant) → Consumer **dedupes** on `idempotencyKey`, buckets by tenant-local day, **additively upserts** Aurora, **appends** raw to S3; malformed → DLQ. Result: aggregates are eventually-consistent, correct regardless of order/lateness; S3 is the rebuild source.

**F2 · Forecast path (batch).** EventBridge fires daily → Forecaster reads Aurora history per `(tenant, category)` → fits a model → produces point + interval + confidence per horizon → **ranks** to top-k → writes **versioned** rows to the Serving Table → emits predicted-vs-actual to Eval/Drift → CloudWatch. A new version is an atomic swap; the old one is the rollback.

**F3 · Read path — forecast mode (happy).** UI → ALB → Service: tenant-scope → Redis **hit?** return. **Miss:** read Serving Table (fresh) → assemble (rank, delta, confidence) → insight present? if not, lazy Bedrock (grounded) + cache → populate Redis → return with `status=fresh` + `as-of`. UI renders table + chart + insight + badge.

**F4 · Read path — actuals mode.** UI → ALB → Service → Redis miss → `ActualsService` aggregate query on Aurora → rank top-k → (optional insight) → cache → return. No model involved — the always-available floor.

**F5 · Degradation (ML plane down / forecast missing).** Forecast-mode read, Serving Table stale/empty → try last-good (flag `stale`) → else **JVM seasonal-naive** from Aurora actuals (flag `degraded`) → else actuals top-k (flag `forecast_pending`). If Bedrock is down anywhere, the **template** insight is used. The UI **always** renders something honest; the read path survives a total ML-plane outage.

---

## G. Data shapes on the wire

```jsonc
// SaleEvent  (C1 → C2 → C3 → S3)
{ "tenantId":"t_123", "orderId":"o_998", "categoryId":"cat_office",
  "amount": 42.50, "currency":"USD", "eventType":"SALE|RETURN|ADJUSTMENT",
  "eventTime":"2026-06-20T14:03:00Z", "idempotencyKey":"o_998:SALE" }

// AggregateRow  (Aurora C4)  PK (tenant_id, category_id, bucket_date)
{ "tenant_id":"t_123", "category_id":"cat_office", "bucket_date":"2026-06-20",
  "sum_amount": 1820.75, "order_count": 37, "currency":"USD" }

// ForecastRow / ServingRow  (Serving Table D3)  pk=tenant#window#mode, sk=rank
{ "pk":"t_123#month#forecast", "sk":"001", "category_id":"cat_office",
  "value": 5400.00, "interval_low": 4900, "interval_high": 5900,
  "confidence":"HIGH", "delta_vs_prior": 0.12, "version": 42, "as_of":"2026-06-28T06:00:00Z" }

// TopKResponse  (Service B2 → UI A1)
{ "tenantId":"t_123", "mode":"forecast", "window":"month", "k":10,
  "status":"fresh|stale|pending|degraded", "asOf":"2026-06-28T06:00:00Z",
  "insight":"Office Supplies is projected to lead next month (~+12%) — consider restocking.",
  "items":[ { "rank":1, "category":"Office Supplies", "value":5400.00,
              "deltaVsPrior":0.12, "confidence":"HIGH",
              "interval":{"low":4900,"high":5900} } ] }
```

> Reading these four shapes top-to-bottom *is* the system: an event becomes an aggregate row, a batch turns aggregate history into a versioned forecast row, and a read assembles forecast rows into the response the dashboard renders.
