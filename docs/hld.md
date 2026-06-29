# Design Document v3 — Multi-Tenant Sales-Forecasting Platform ("Top Sales by Category")

**Status:** v3 (consolidated) · **Supersedes** HLD v2 and folds in the component deep-dive, edge/data-flow catalog, and data shapes. · **Scope:** production-ready, single-account / single-VPC / single-region.

## Contents
1. Context & Overview · 2. Goals & Non-Goals · 3. Assumptions · 4. Requirements (FR/NFR) · 5. System-Level Approaches (Comparison & Recommendation) · 6. Recommended Architecture · 7. Presentation Tier · 8. Component Deep-Dive · 9. Connections & Data-Flow Catalog · 10. End-to-End Flows · 11. Data Shapes · 12. Decision Records (Considered Approaches) · 13. AI Integration · 14. Code Changes A→B→C · 15. Scalability · 16. Failure & Resilience · 17. Security & Privacy · 18. Observability · 19. Infrastructure & Local-Runnable · 20. FAQ · 21. Appendix

---

## 1. Context & Overview
Sellers need to see **which product categories drive their sales** — what *has* sold (trailing actuals) and what is *likely* to sell (forecast) — over selectable windows (week / month / year), **viewed through a simple interface**. This informs inventory, pricing, and marketing.

The system is a **multi-tenant forecasting + ranking service with a user-facing dashboard**: ingest sale/return events → maintain per-tenant category aggregates → forecast each category forward → rank to **top-k** → surface a grounded **natural-language insight** → present in a **dashboard UI**. Core shape: **forecasting engine → top-k read model → presentation tier.**

**Implementation-language strategy:** the runnable system is **all Java / Spring Boot** plus a thin static dashboard. The Python / SageMaker model is a *designed* second implementation behind the `Forecaster` interface — single-language and low-risk for v1.

---

## 2. Goals & Non-Goals
**Goals:** rank top-k categories per tenant per window in **forecast** and **actuals** modes; accurate idempotent aggregates from an event stream; a grounded NL insight; a **user-friendly dashboard**; high read availability, bounded freshness/cost, adaptable scale.

**Non-Goals (scope cuts, each tied to an assumption):** identity/auth (upstream), order capture (upstream), category taxonomy (catalog service), cross-account/multi-region networking (single VPC/region v1), a bespoke ML training platform (baseline + interface until accuracy data justifies more), the broader commerce platform.

---

## 3. Assumptions
| # | Assumption | Enables |
|---|---|---|
| A1 | Identity/authz upstream; tenant context trusted | No auth subsystem; still enforce tenant scoping |
| A2 | Sale/return/adjustment events from upstream | No order-capture path |
| A3 | Category taxonomy on the catalog service | No taxonomy management |
| A4 | Read-heavy; forecasts evolve slowly (daily refresh ok) | **Precompute** over per-request inference |
| A5 | ~1M tenants; ~100 orders/tenant/day; tens–100 categories/tenant | Capacity sizing |
| A6 | One reporting currency + known timezone per tenant | Tenant-local bucketing |
| A7 | "Consistency" = accurate idempotent aggregates + coherent ranked set; forecasts eventually consistent | Consistency contract |
| A8 | Single region/VPC acceptable v1 | No cross-region HA |
| A9 | UI is a read-only dashboard (small concurrent users/tenant) | Thin, cache-friendly presentation tier |

---

## 4. Requirements

### 4.1 Functional
- **FR1** Top-k categories by sales, per tenant, per selectable window.
- **FR2** Two read modes: **forecast** (predicted) and **actuals** (historical).
- **FR3** Per category: rank, value, delta vs prior, confidence (forecast).
- **FR4** Grounded NL insight summarizing top categories/trends.
- **FR5** Ingest events; maintain accurate per-`(tenant, category, bucket)` aggregates.
- **FR6** Refresh forecasts on cadence within the freshness SLO.
- **FR7** Strict tenant scoping on every read.
- **FR8** Surface status (`fresh|stale|pending|degraded`) + as-of timestamp.
- **FR9 (UI)** A dashboard to pick mode/window/k and **view** ranked categories, a forecast-vs-actual chart, the insight, and freshness.

### 4.2 Non-Functional (prioritized)
| Rank | NFR | Target |
|---|---|---|
| 1 | Availability (read path) | 99.9%+; reads survive a full ML-plane outage via degradation |
| 2 | Correctness / Reliability | Idempotent exactly-once-*effect* ingestion; accurate aggregates; coherent ranked set |
| 3 | Performance | API read p99 < ~150 ms; fast dashboard first paint |
| 4 | Freshness | Forecasts within cadence for ≥X% tenants; actuals bounded by ingestion lag |
| 5 | Cost-efficiency | Precompute + cache; LLM cost bounded (lazy + small model) |
| 6 | Scalability / Adaptability | ~1M+ tenants, ~100M+ events/day; flexes up/down |
| 7 | Durability | No data loss; S3 raw log durable; downstream regenerable |
| 8 | Security / Privacy | Tenant isolation; encryption; untrusted-input handling; least privilege |
| 9 | Usability | Clear dashboard; honest confidence/staleness display |
| 10 | Maintainability / Evolvability | Interfaces enable approach + local↔cloud swaps; IaC |
| 11 | Observability | System + ML-quality metrics; SLOs; alerting |

---

## 5. System-Level Approaches — Comparison & Recommendation
The **top-level architectural fork**: *where and when forecasts are computed.* Three viable approaches, compared across the dimensions that matter, then a recommendation tied to the assumptions.

| Dimension | A — Precompute / Batch | B — On-demand / Real-time | C — Hybrid / Lambda |
|---|---|---|---|
| Freshness | up to one cadence stale | freshest | fresh recent + batch base |
| Read latency | lowest (O(top-k) read) | high (inference in path) | low (base) + small merge |
| Cost | lowest (amortized) | highest (per-request compute) | medium |
| Availability | highest (ML offline) | ML availability = read availability | high (degrades to base) |
| Complexity | lowest | medium | highest (two paths + merge) |
| ML in hot path? | no | yes | speed layer only |

**Approach A — Precompute / Batch.** A scheduled job forecasts every series offline and writes precomputed top-k to a serving store; the API serves precomputed rows.
- *Advantages:* O(top-k) reads; high availability (ML offline ≠ read outage); lowest cost; simplest hot path.
- *Disadvantages:* staleness up to one cadence; arbitrary/odd horizons may not be precomputed.

**Approach B — On-demand / Real-time.** Forecast at request time (or via a streaming model endpoint), short cache.
- *Advantages:* maximal freshness; arbitrary windows on demand.
- *Disadvantages:* inference latency + cost in the hot path; ML availability becomes read availability; expensive at a 1M-tenant fan-out.

**Approach C — Hybrid / Lambda.** Precomputed batch base + a speed layer for recent data, merged at read.
- *Advantages:* batch economics + near-real-time freshness; degrades to base if the speed layer fails.
- *Disadvantages:* highest complexity — two code paths plus merge/reconciliation.

**Recommendation: Approach A**, justified directly by the assumptions:
- **A4 (read-heavy, slow-evolving, daily freshness acceptable)** → precompute's staleness is a non-issue, and its availability/latency/cost advantages dominate.
- **A5 (~1M tenants)** → B's per-request inference cost is prohibitive at this fan-out; precompute amortizes it.
- The **actuals read mode + degradation chain** already cover "top-k in the last N units" and not-yet-precomputed cases — without paying B's cost.

**What I intentionally did NOT build:** a real-time inference path or a speed layer — neither is justified under the current freshness requirement (avoids over-engineering).

**If the assumptions change:**
- *Freshness requirement → sub-hour:* migrate to **C** (add a speed layer; keeps batch economics) — **not** B, unless precompute also becomes uneconomical (extreme query variety), in which case B.
- The migration is a **`ForecastProvider` swap behind a stable interface**, not a rewrite (§14) — so committing to A now is cheaply reversible.

---

## 6. Recommended Architecture (four tiers)

```mermaid
flowchart TB
    subgraph Presentation Tier
      UI["Dashboard UI<br/>demo: static, served by Spring Boot<br/>prod: React SPA on Vercel<br/>(S3 + CloudFront = AWS-native alt)"]
    end
    subgraph Ingestion Plane
      EV["Upstream sale/return events"] --> ST["Kinesis / MSK"]
      ST --> CO["Ingestion Consumer"]
      CO -->|idempotent upsert| AGG["Aurora Postgres<br/>(tenant, category, channel, day) rollups"]
      CO -->|append| RAW["S3 Raw Event Log (SoT)"]
      CO -. malformed .-> DLQ["DLQ"]
    end
    subgraph Forecast Plane (batch)
      SCH["EventBridge cron"] --> FJ["Forecaster Job<br/>Java baseline / SageMaker (future)"]
      AGG --> FJ
      FJ -->|versioned| SRV["Serving Table<br/>precomputed top-k + intervals"]
      FJ --> EVAL["Eval / Drift (WAPE, bias)"]
    end
    subgraph Serving Plane
      UI --> APIGW["API Gateway / ALB"]
      APIGW --> API["Spring Boot Service (stateless)"]
      API --> CA["Redis cache"]
      API --> SRV
      API --> AGG
      API -. lazy, cached .-> BR["Bedrock (grounded insight)"]
      API -. ML plane down .-> FB["JVM baseline fallback"]
    end
    EVAL --> MON["CloudWatch / dashboards"]
```

**Tiers:** Presentation (read-only dashboard, pure view). Ingestion (durable capture + idempotent rollups). Forecast (batch forecast → versioned serving rows). Serving (stateless, cache-fronted reads with degradation). Forecast and Serving couple **only through the Serving Table** — no synchronous ML on the read path.

---

## 7. Presentation Tier (UI)
**FR9** — the seller views top sales by category through a user-friendly interface.

**Demo (built):** a lightweight dashboard — static HTML + vanilla JS + **Chart.js (CDN)** — served from Spring Boot static resources. Single deployable, **no Node toolchain**. Controls (mode/window/k), ranked table, forecast-vs-actual chart, insight, status/as-of badge.

**Production (designed):** a **React SPA** (Vite + Recharts) deployed to **Vercel** (git-push deploys, preview URLs, TLS + CDN, zero infra), calling the same API. **S3 + CloudFront in-account is the documented AWS-native alternative.**

> **Hosting trade-off (defend):** Vercel is a third-party origin outside AWS → the API must allow it via **CORS** (S3+CloudFront could be same-origin) and the story is "decoupled frontend hosting" vs single-cloud. Deliberate choice. **Demo caveat:** the live demo runs the local Spring-Boot dashboard (no internet dependency); Vercel is the "and here it is in prod" link, not a dependency. The UI is intentionally **thin** — a backend-design role, so the deep-dive stays on forecasting/serving/AI.

---

## 8. Component Deep-Dive
Per box: responsibility · in/out · key internals · cloud/local tech · failure.

**Dashboard UI** — read-only view; controls + table + chart + insight + status badge; explicit loading/empty/error/degraded states. *In:* user actions. *Out:* `GET /tenants/{id}/top-categories`. *Tech:* static-on-Spring (demo) / React-on-Vercel (prod). *Fails:* renders something honest on degraded data; never crashes.

**API Gateway / ALB** — entry point: TLS, routing, health-aware distribution; (API GW) rate-limit/throttle, CORS. *Internals:* health checks pull unhealthy tasks; CORS allow-list = Vercel/localhost; 429 throttle, 503 no-targets. *Local:* hit the service directly.

**Spring Boot Service (stateless)** — the orchestrator; assembles the response via a 7-step pipeline: (1) `TenantScopeFilter` authz → (2) `CacheShell` lookup → (3) mode routing → (4) `ForecastProvider` reads Serving Table *or* `ActualsService` aggregates Aurora → (5) degradation chain → (6) `InsightGenerator` lazy+cached → (7) assemble (rank, delta, confidence, status, as-of). *Tech:* Fargate, autoscaled; Spring profile swaps impls. *Fails:* every downstream has a fallback; stateless → any instance serves any request.

**Redis cache** — caches assembled top-k. *Internals:* key `(tenant,window,mode,k)`; jittered TTL; **event-driven invalidation** via per-tenant version bump; single-flight on miss. *Fails:* down → fall through to Serving Table/DB.

**Bedrock (grounded insight)** *(lazy, cached)* — verbalizes **only provided numbers**; output validated; guardrail (prod); template fallback. *Internals:* `InvokeModel` via Java SDK; small model from SSM config. *Fails:* timeout/breaker → template; never blocks. *Cost:* only top-k, only viewed tenants, cached.

**JVM baseline fallback** *(ML plane down)* — computes a forecast **in-process** from actuals (seasonal-naive arithmetic, no Python/ML); marks `degraded`/`forecast_pending`. Keeps the read path alive through a total ML-plane outage.

**Upstream events** — source (A2); emits `SaleEvent` (sale/return/adjustment).

**Kinesis / MSK** — durable, partition-ordered ingestion buffer; partition by `tenantId`; retention → replay; absorbs bursts. *Local:* `POST /events` (no stream).

**Ingestion Consumer** — dedupe on `idempotencyKey` → bucket by tenant-local day → **additive upsert** Aurora → **append** S3 → malformed → DLQ; KCL checkpointing. At-least-once + idempotency = **exactly-once effect**.

**Aurora Postgres (rollups)** — authoritative aggregate store; serves actuals + feeds the forecaster. Row `(tenant_id, category_id, channel, bucket_date) → sum_amount, order_count, currency` (channel is a first-class key dimension — DR-9; added in Phase 2.5); partition by tenant + replicas at scale; isolated subnets.

**S3 Raw Event Log (SoT)** — durable immutable source of truth; the only precious data; replay rebuilds everything downstream.

**DLQ** — quarantine malformed/poison events; one bad event never stalls a partition; alert. *Tech:* SQS (cloud) / quarantine table (local).

**EventBridge cron** — triggers the batch forecaster on the daily cadence (A4).

**Forecaster Job** — read aggregates → forecast per `(tenant, category, channel)` × horizon (summed up to `all`) → rank → write **versioned** serving rows + intervals → emit eval metrics. Cold-start handling; partition by tenant (parallel); incremental refit + tiered cadence at scale. *Tech:* Java baseline on scheduled Fargate (built); SageMaker batch transform (designed) behind the same interface.

**Serving Table** — read-optimized store the API serves forecasts from; point-lookup `pk=tenant#window#mode#channel, sk=rank` (channel in the key — DR-9; default view `all` is the summed rollup); **versioned** for atomic swap + rollback. *Tech:* DynamoDB (cloud) / Postgres table (local) behind `ForecastProvider`.

**Eval / Drift** — backtest WAPE + bias; rolling error → threshold → refit/alert; champion/challenger gating. Emits to CloudWatch.

**CloudWatch / dashboards** — observability sink; system (RED/USE, stream lag, batch time, freshness SLO) + ML-quality (WAPE, bias, drift, cold-start %, insight faithfulness); alarms → SNS.

---

## 9. Connections & Data-Flow Catalog
| # | From → To | Trigger | Payload | Sync? | On failure |
|---|---|---|---|---|---|
| 1 | UI → ALB | control change | GET + (tenant via auth, mode, window, k) | sync | UI shows error/degraded |
| 2 | ALB → Service | routed | tenant-scoped HTTP | sync | 503/429; healthy routing |
| 3 | Service ↔ Redis | every read | key ↔ serialized response | sync | fall through to DB |
| 4 | Service → Serving Table | forecast miss | read by `pk` | sync | degradation chain |
| 5 | Service → Aurora | actuals/degradation | aggregate range-query | sync | last cache/replica |
| 6 | Service ⇢ Bedrock | insight absent | prompt(numbers) → text | sync (bounded) | template fallback |
| 7 | Service → JVM fallback | forecast missing/stale | in-process; reads aggregates | sync | actuals + `forecast_pending` |
| 8 | Upstream → Kinesis | event | `SaleEvent` | async | producer retries |
| 9 | Kinesis → Consumer | poll | `SaleEvent` batch | async | lag → scale; replay |
| 10 | Consumer → Aurora | per event | idempotent upsert | sync | retry; checkpoint resume |
| 11 | Consumer → S3 | per event | append raw event | async | retry; durable |
| 12 | Consumer ⇢ DLQ | malformed | bad event + reason | async | alert; reprocess |
| 13 | EventBridge → Forecaster | daily | trigger | async | next tick; idempotent re-run |
| 14 | Aurora → Forecaster | batch | history per series | sync (batch) | retry segment |
| 15 | Forecaster → Serving Table | per series | versioned `ForecastRow` | sync (batch) | keep last-good version |
| 16 | Forecaster → Eval/Drift | per batch | predicted vs actual | sync (batch) | metric gap alarmed |
| 17 | Eval/Drift → CloudWatch | per batch | WAPE/bias/drift | async | — |

---

## 10. End-to-End Flows
**Write (ingestion):** Upstream → Kinesis (by tenant) → Consumer dedupes, buckets tenant-local day, **additively upserts** Aurora, **appends** S3; malformed → DLQ. Aggregates correct regardless of order/lateness; S3 is the rebuild source.

**Forecast (batch):** EventBridge → Forecaster reads Aurora history → fits per series → point + interval + confidence per horizon → ranks top-k → writes **versioned** Serving rows → emits predicted-vs-actual to Eval/Drift → CloudWatch. New version = atomic swap; old = rollback.

**Read — forecast (happy):** UI → ALB → Service: tenant-scope → Redis hit? return. Miss → Serving Table (fresh) → assemble → insight (lazy Bedrock + cache) → populate Redis → return `status=fresh` + as-of.

**Read — actuals:** UI → ALB → Service → Redis miss → `ActualsService` aggregates Aurora → rank → cache → return. No model — the always-available floor.

**Degradation (ML down / forecast missing):** last-good (`stale`) → **JVM seasonal-naive** from actuals (`degraded`) → actuals top-k (`forecast_pending`). Bedrock down → template insight. The UI **always** renders something honest; the read path survives a total ML-plane outage.

---

## 11. Data Shapes
```jsonc
// SaleEvent  (Upstream → Kinesis → Consumer → S3)
{ "tenantId":"t_123","orderId":"o_998","categoryId":"cat_office","channel":"ONLINE",
  "amount":42.50,"currency":"USD","eventType":"SALE|RETURN|ADJUSTMENT",
  "eventTime":"2026-06-20T14:03:00Z","idempotencyKey":"o_998:SALE" }

// AggregateRow  (Aurora)  PK (tenant_id, category_id, channel, bucket_date)
{ "tenant_id":"t_123","category_id":"cat_office","channel":"ONLINE","bucket_date":"2026-06-20",
  "sum_amount":1820.75,"order_count":37,"currency":"USD" }

// ForecastRow / ServingRow  (Serving Table)  pk=tenant#window#mode#channel, sk=rank
{ "pk":"t_123#month#forecast#all","sk":"001","category_id":"cat_office","value":5400.00,
  "interval_low":4900,"interval_high":5900,"confidence":"HIGH",
  "delta_vs_prior":0.12,"version":42,"as_of":"2026-06-28T06:00:00Z" }

// TopKResponse  (Service → UI)
{ "tenantId":"t_123","mode":"forecast","window":"month","channel":"all","k":10,
  "status":"fresh|stale|pending|degraded","asOf":"2026-06-28T06:00:00Z",
  "insight":"Office Supplies is projected to lead next month (~+12%) — consider restocking.",
  "items":[{"rank":1,"category":"Office Supplies","value":5400.00,"deltaVsPrior":0.12,
            "confidence":"HIGH","interval":{"low":4900,"high":5900}}] }
```
> Read top-to-bottom, these four shapes *are* the system: event → aggregate row → versioned forecast row → response.
>
> **`channel` (`ONLINE | OFFLINE`) is a first-class dimension** of the aggregate and serving keys (see **§12 DR-9**, ADR-0010). The API exposes `channel=all|online|offline` (default `all`); `all` is the summed rollup — forecasts are fit at channel grain and summed up to `all`. *(Built in **Phase 2.5**; the Phase 2 walking skeleton ships the 3-tuple key `(tenant, category, day)` and gains `channel` via a follow-up migration.)*

---

## 12. Decision Records (Considered Approaches)
Each decision follows the same shape: **options with pros/cons → recommendation → why (requirements & assumptions) → if the assumption changes.** *(The top-level forecast-strategy fork — precompute vs on-demand vs hybrid — is recorded in §5.)*

**DR-1 · Java ↔ model coupling — data plane vs synchronous RPC**
- *Option A — Data-plane coupling (chosen):* the model writes the serving table; the service reads it. **Pros:** ML latency/availability never enter the read budget; clean separation. **Cons:** forecasts only as fresh as the last batch.
- *Option B — Synchronous RPC (REST/gRPC) per request:* the service calls the model live. **Pros:** fresh. **Cons:** ML availability becomes read availability; latency + cost in the hot path.
- **Recommendation:** A. **Why:** the A4 batch cadence makes per-request RPC pointless, and availability (NFR1) is the top priority. **If real-time scoring is required:** add a SageMaker real-time endpoint (REST) behind the `Forecaster` seam; reserve gRPC for self-hosted sub-10ms/streaming needs.

**DR-2 · Aggregate store — Aurora Postgres vs DynamoDB**
- *Option A — Aurora Postgres (chosen for aggregates):* relational range/group-by, transactions, indexing. **Pros:** natural for aggregation access patterns; integrity. **Cons:** needs partitioning + replicas at scale.
- *Option B — DynamoDB:* key-value at extreme scale. **Pros:** predictable horizontal scaling. **Cons:** awkward for range/group-by analytics.
- **Recommendation:** Aurora for **aggregates** + a **DynamoDB-shaped serving table** for the point-lookup read path (best of both). **Why:** aggregation is relational; serving is a point lookup (DR-3 below). **If** aggregate access becomes pure KV at extreme write rates → move aggregates to DynamoDB; **if** heavy analytical scans dominate → a columnar/warehouse engine.

**DR-3 · Serving store shape — relational rows vs key-value point-lookup**
- *Option A — KV point-lookup (chosen):* `pk = tenant#window#mode` (gains `#channel` in Phase 2.5 — DR-9), `sk = rank`; the whole top-k set is one partition read. **Pros:** O(top-k) reads, trivially cacheable, scales flat. **Cons:** denormalized, write-on-refresh.
- *Option B — Query the aggregate/relational store at read time:* **Pros:** no extra store. **Cons:** ranking/aggregation on the hot path → slower, scales with data.
- **Recommendation:** A (precomputed KV serving table). **Why:** read-heavy (A4) + p99 target (NFR3). **If** k or category cardinality explodes → secondary indexing / pagination.

**DR-4 · Forecasting model — Java baseline vs Python/SageMaker ML**
- *Option A — Java baseline (built):* Holt-Winters / seasonal-naive (arithmetic). **Pros:** single-language, runnable, interpretable, no training infra; strong on regular seasonal series. **Cons:** weaker on sparse/irregular series.
- *Option B — Python/SageMaker global model (designed):* DeepAR / GBDT on lag+calendar features. **Pros:** cross-learning, cold-start, sparse-series strength. **Cons:** training infrastructure, lower interpretability.
- **Recommendation:** baseline now, ML behind the **same `Forecaster` interface**, promoted **per-segment** when accuracy data justifies it. **Why:** well-scoped v1; the A5 fan-out is parallelizable in batch regardless of model. **If** accuracy data shows broad baseline failure → promote ML more widely.

**DR-5 · Accuracy metric — WAPE vs MAPE**
- *Option A — WAPE (chosen):* Σ|actual−forecast| / Σ|actual|. **Pros:** scale-aware, robust to zero/near-zero periods. **Cons:** less intuitive than a per-point percentage.
- *Option B — MAPE:* mean(|actual−forecast| / |actual|). **Pros:** familiar. **Cons:** explodes on near-zero sales (common in long-tail categories).
- **Recommendation:** WAPE (+ bias). **Why:** long-tail categories have near-zero periods where MAPE is misleading.

**DR-6 · GenAI insight generation — lazy-cached vs precompute-all**
- *Option A — Lazy + cached (chosen):* generate on first view, then cache. **Pros:** pay only for viewed tenants; bounded cost. **Cons:** a synchronous Bedrock call on the cold path (timeout + fallback).
- *Option B — Precompute all in batch:* generate for every series. **Pros:** always present. **Cons:** ~150M generations/day — wasteful and expensive.
- **Recommendation:** lazy + cached + small model + deterministic template fallback. **Why:** cost-efficiency (NFR5). **If** insights must be present pre-view (e.g. emailed digests) → precompute for that cohort only.

**DR-7 · Read modes — two modes vs forecast-only**
- *Option A — Two modes (chosen):* forecast + actuals behind one API. **Pros:** actuals is pure aggregation → always available; the durable floor; covers "last N units." **Cons:** two read paths to maintain.
- *Option B — Forecast-only:* simpler API. **Cons:** no floor when forecasts aren't ready; can't answer "last N actual."
- **Recommendation:** two modes. **Why:** availability (NFR1) + the requirement for selectable time frames implies both historical and predicted.

**DR-8 · UI hosting — Spring-served / Vercel / S3+CloudFront**
- *Option A — Static dashboard served by Spring Boot (demo run):* **Pros:** single deployable, no Node, runs offline. **Cons:** not a production-grade SPA.
- *Option B — React SPA on Vercel (production deploy):* **Pros:** git-push deploys, preview URLs, TLS+CDN, zero infra. **Cons:** cross-origin (needs CORS); third-party origin (not single-cloud).
- *Option C — React SPA on S3 + CloudFront (AWS-native alt):* **Pros:** single-cloud, can be same-origin, in-account. **Cons:** more infra to write (bucket, distribution, OAC, cache policies).
- **Recommendation:** serve static from Spring Boot for the **live demo**; **Vercel** for the production deploy; **S3+CloudFront** documented as the AWS-native alternative. **Why:** demo reliability + portfolio speed; it's a config decision, not an architecture change (same static build, same API). **If** single-cloud is a hard requirement → S3+CloudFront.

**DR-9 · Channel — first-class dimension vs data-only enrichment**
- *Option A — Channel as a first-class dimension (chosen):* `channel` (`ONLINE | OFFLINE`) joins the aggregate key `(tenant, category, channel, day)` and the serving key `tenant#window#mode#channel`; forecasts are fit at channel grain and summed up to `all`. **Pros:** online vs offline can be ranked/forecast independently (genuinely different seasonality — offline Black-Friday vs online Cyber-Monday peaks); `all` stays exact as the sum. **Cons:** widens the key → more aggregate/serving rows and more forecast fits (cardinality × channels).
- *Option B — Data-only enrichment (post-filter):* keep the `(tenant, category, day)` key; carry channel only as a non-key attribute and filter at read time. **Pros:** no key change, fewer rows/fits. **Cons:** can't rank or forecast per channel — the differentiated seasonality collapses to a flat split.
- **Recommendation:** A — channel in the key, default API view `channel=all`. **Why:** the differentiated per-channel seasonality only exists if channel is modeled, not filtered. **If** channel cardinality/fan-out becomes a problem → collapse low-volume channels into `all` and treat channel as a post-filter (degrades gracefully to Option B behind the same API). *(Built in Phase 2.5 — see ADR-0010.)*

---

## 13. AI Integration
**Forecasting:** Java baseline = Holt-Winters level/trend/seasonal (+ seasonal-naive). Designed ML upgrade = global model (DeepAR / GBDT on lag+calendar) for cold-start/sparse; Croston for intermittent; outlier dampening; holiday calendar. Pipeline: aggregates → features → forecast → rank → versioned rows + intervals. Eval: **time-series CV** backtest, **WAPE** + bias. Drift: rolling WAPE → threshold → refit/alert; champion/challenger.

**GenAI insight (bounded, grounded):** LLM verbalizes only provided numbers; output validated; template fallback. Bedrock small model via Java SDK; lazy + cached. Safety: descriptive not advisory; tenant text untrusted (prompt-injection); guardrail; no tools/write. Eval: faithfulness (LLM-judge + spot) + action-taken.

> Language profile: the runnable system uses the Java baseline; the Python/SageMaker path is described and defended at the mechanics level behind the `Forecaster` seam.

---

## 14. Code Changes A → B → C
Stable across all: API contract, controller, DTOs, tenant scoping, caching shell, **UI**, actuals path, observability. Only the **`ForecastProvider` impl** + presence of batch/speed components change.

| Component | A Precompute | B On-demand | C Hybrid |
|---|---|---|---|
| `ForecastProvider` | `Precomputed…` | `OnDemand…` (+timeout/breaker/cache) | `Merging…` (base+speed) |
| Batch job | present | removed/warmer | present (base) |
| Speed layer | — | — | new |
| Sync inference on read | none | added | speed layer only |

**A→B:** swap provider + add a sync inference client behind `Forecaster`. **A→C:** keep batch base + add speed layer + merging provider. A provider swap, not a rewrite.

---

## 15. Scalability & Performance
Reads scale-independent (precompute → O(top-k), Redis-fronted, stateless autoscale). UI assets CDN-served (prod). **10×:** partition aggregates by tenant + replicas; batch parallelism until the **refresh window** is the limit → **incremental refit** + **tiered cadence**. **Autoscaling stops at** the refresh-window limit + hot-partition skew → design levers. First bottleneck: batch window + celebrity-tenant partitions; reads least likely to break.

---

## 16. Failure Modes & Resilience
Read-path degradation: last-good → JVM seasonal-naive → actuals `forecast_pending`. **Read path survives a total ML-plane outage; UI always renders honestly.** Ingestion lag → stream buffers, consumers autoscale. Poison event → DLQ + alert. Redis/DB/Bedrock/SageMaker down → defined fallbacks. Bad forecast → validation + champion/challenger + versioned rollback + recompute from aggregates. Bulkheads between tiers + circuit breakers/timeouts on sync calls. S3 raw log is the only precious data; idempotent replay rebuilds everything.

---

## 17. Security & Privacy
Tenant isolation on every query (never trust a body-supplied tenant id). Prompt-injection: tenant category names as delimited data, validated output, guardrail, no tools. Global-model privacy (when added): aggregate-pattern learning, exclude identifying features, validate for leakage. Encryption in transit/at rest, least-privilege IAM, audit logging, PII minimization. **CORS:** API allow-lists the UI origin — `localhost`, the **Vercel** domain (cross-origin), or same-origin via S3+CloudFront.

---

## 18. Observability & Operations
System metrics (RED, USE, stream lag, batch time, **freshness SLO**) + **ML-quality** (rolling WAPE, bias, drift, cold-start %, insight faithfulness). UI RUM (prod): first-paint, error rate. SLOs: read availability 99.9%+, read p99, freshness. Deploys: blue/green service, canary + champion/challenger for models, versioned tables for flip rollback.

---

## 19. Infrastructure & Local-Runnable
**Production (AWS CDK, TypeScript), 5 stacks:** **Network** (VPC + endpoints) → **Storage** (S3, Kinesis, DLQ, Aurora, DynamoDB serving table, Redis) + **Intelligence** (Bedrock policy + guardrail, model config, SageMaker registry/role, ML ECR, artifact bucket) → **Application** (ECS Fargate serving service, consumer, batch forecaster, app ECR repos) → **Monitoring** (dashboards, alarms). The SPA deploys to **Vercel** (outside AWS; API allow-lists the origin via CORS); S3+CloudFront is the documented in-account alternative.

**Local-runnable (no AWS account) — same code, swapped impls behind interfaces, selected by Spring profile (`local` vs `aws`):**
| Production (AWS) | Local stand-in |
|---|---|
| Kinesis ingestion | REST `POST /events` |
| DynamoDB serving table | Postgres table |
| Aurora aggregates | Postgres in Docker |
| ElastiCache Redis | Redis in Docker |
| S3 raw log | local filesystem dir |
| Bedrock insight | `TemplateInsightGenerator` |
| SageMaker model | Java baseline forecaster |
| React SPA on Vercel (S3+CloudFront = AWS-native alt) | static dashboard served by Spring Boot |

**Local prerequisites: Docker Desktop, JDK 21, Maven, a browser.** No Node, no AWS account. Run: `docker-compose up` → `make run` → `make seed` → open the dashboard.

---

## 20. FAQ
- **"Interface" = API or UI?** Both: API is the contract; FR9 requires a dashboard (§7).
- **Forecasting or aggregation?** Both — forecasting feeds top-k; actuals is the durable floor.
- **Why precompute?** A4 → best availability/latency/cost; removes ML from the hot path.
- **Java↔Python coupling?** Data-plane (batch); ML never in the read budget.
- **Run without AWS?** Yes — §19; one `docker-compose up`.
- **Built vs designed?** Built: ingestion, aggregation, Java forecaster + eval, two-mode API, caching, degradation, grounded insight, dashboard, 5-stack CDK. Designed: Kinesis, DynamoDB serving, Python/SageMaker, speed layer (C), React SPA (Vercel; S3+CloudFront alt), multi-region.
- **Why Vercel, not S3+CloudFront?** Demo/portfolio: git-push deploys, preview URLs, TLS+CDN, zero infra. Trade-off: cross-origin (CORS), third-party origin. S3+CloudFront is the AWS-native alternative — a config choice, not an architecture change.
- **10× scale?** Incremental refit + tiered cadence + partitioning; autoscaling alone won't fix the refresh window.

---

## 21. Appendix
**Capacity:** ~1M tenants × ~100 orders/day ≈ **100M events/day** (~1–2K eps avg, ~10–20K peak). Forecast fan-out ≈ ~1M × ~50 × 3 ≈ **~150M series-forecasts/day**, parallel by tenant. Serving table ≈ tens of millions of small rows — cacheable.
**Glossary:** WAPE = Σ|actual−forecast|/Σ|actual|; Holt-Winters = triple exponential smoothing; Croston = intermittent-demand method; DeepAR = global RNN forecaster; champion/challenger = shadow-evaluate before promotion; bulkhead = failure-isolation boundary.
