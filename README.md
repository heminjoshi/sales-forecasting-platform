# Sales-Forecasting Platform

[![ci](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml)

> Multi-tenant "top sales by category" platform — ingests sale events, maintains per-tenant
> category aggregates, forecasts each category forward, ranks to a top-k, surfaces a grounded
> natural-language insight, and presents it in a dashboard. Java / Spring Boot, runnable locally
> with one `docker compose up`; AWS path designed in CDK behind the same interfaces.

 > **Status:** 🟢 Runnable through **Phase 5**. Ingest events → idempotent per-tenant, per-channel
> category aggregates → ranked top-k over the REST API → served dashboard. On top of that:
> the **channel** first-class dimension + a deterministic seasonal synthetic-data generator
> (Phase 2.5), a **central config** surface (`TopsalesProperties`), the **forecasting engine**
> (Phase 3 — seasonal-naive / Holt-Winters behind a `Forecaster` seam, a batch that writes
> **versioned, ranked serving rows** per tenant×window×channel with the channels rolled up to `all`,
> and a **WAPE backtest** report), the forecast **read path** (Phase 4 — `mode=forecast` serves
> the precomputed serving rows through a `ForecastProvider` behind a **Redis cache**, down a **4-tier
> degradation chain that never fails closed** — `fresh`→`stale`→seasonal-naive `degraded`→actuals
> `pending` — with the dashboard surfacing a **status badge**, `asOf`, confidence, and prediction
> intervals), and the **grounded GenAI insight** layer (Phase 5 — a deterministic template floor
> behind the `InsightGenerator` seam, `GroundingValidator`-checked, injection-safe, lazy+cached in the
> existing per-tenant Redis key). Next: the AWS CDK + productionization (Phases 6–8).

## Quick start

```bash
make up      # start Postgres + Redis locally
make run     # build + start the API on http://localhost:8080 (Flyway migrates on boot)
```

Then open <http://localhost:8080> for the dashboard and POST a few sample events (the
`postman/` collection has a ready sequence), e.g. for the seeded `t_demo` tenant:

```bash
curl -H "Content-Type: application/json" -H "X-Tenant-Id: t_demo" \
  -X POST http://localhost:8080/api/v1/events \
  -d '{"orderId":"o1","categoryId":"Office Supplies","channel":"ONLINE","amount":120.00,"currency":"USD","eventType":"SALE","eventTime":"2026-06-20T14:03:00Z"}'

# channel = all | online | offline (default all); all is the summed rollup
curl -H "X-Tenant-Id: t_demo" \
  "http://localhost:8080/api/v1/tenants/t_demo/top-categories?mode=actuals&window=month&channel=all&k=10"
```

For a realistic demo, seed the data and run the forecast batch:

```bash
make seed        # backfill months of seasonal, channel-split history for both demo tenants
make trickle     # (optional) post live events that continue it so the dashboard moves
make forecast    # batch: fit forecasters → write ranked, versioned serving rows (per tenant×window×channel)
make eval        # backtest the forecasters → regenerate docs/forecast-eval-report.md (WAPE + bias)
```

The dashboard's **tenant** picker is populated from `GET /api/v1/tenants`; two demo tenants
(`t_demo`, `t_acme`) are seeded with independent data, so you can flip between them to see
multi-tenant isolation. The dashboard's window/channel/`k` controls are config-driven from
`GET /api/v1/config` — every tweakable (the `k` choices, window lengths, validation bounds, forecast
params) lives in one place, `topsales.*` in `application.yml` (bound to `TopsalesProperties`).

Prerequisites: Docker Desktop, JDK 21+, Maven, a browser. No Node, no AWS account required.
Tests: `make test` (fast unit tests, no Docker) · `make verify` (adds Testcontainers integration tests).

## Architecture

_Diagram and overview — to be added._

Four tiers: **presentation** (dashboard) → **serving** (REST API) → **forecast** (batch) →
**ingestion**. Local impls and AWS impls sit behind the same interfaces, selected by Spring profile.

## Tech stack

- **Service:** Java 21, Spring Boot 4.1, Maven multi-module (`service/`).
- **Local stack:** Postgres + Redis via Docker Compose (`local/`).
- **Infra (designed):** AWS CDK, TypeScript (`infra/`).
- **UI:** static dashboard served by the API (demo); React SPA on Vercel (designed, `web/`).

## Documentation

- [`docs/hld.md`](docs/hld.md) — high-level design (consolidated design doc) · [`docs/component-deep-dive.md`](docs/component-deep-dive.md)
- [`docs/lld.md`](docs/lld.md) — low-level design (the implementation contract: DDL, interfaces, pipelines)
- [`docs/adr/`](docs/adr/) — 10 comparative architecture decision records · [`docs/api/openapi.yaml`](docs/api/openapi.yaml) — REST contract
- [`docs/diagrams/`](docs/diagrams/) — architecture, data-flow, ERD, sequence, UI-flow · [`docs/runbook.md`](docs/runbook.md) — alarms, degradation, recovery
- [`test-plan/`](test-plan/) — integration, load, stress, canary, and manual-QA test plans

## Built vs. designed

- **Built & runnable now (Phases 0–5):**
  - **Ingestion** — idempotent additive aggregation, tenant-local bucketing, dedupe + raw log +
    quarantine; the `channel` (`ONLINE`/`OFFLINE`) **first-class key dimension**
    ([ADR-0010](docs/adr/0010-channel-as-first-class-dimension.md), Phase 2.5).
  - **Read API & dashboard** — the two-mode read API (`channel`/window/`k`, window from/to);
    `TenantScopeFilter` multi-tenant isolation; RFC 7807 errors; a config-driven served dashboard.
  - **Synthetic data** (Phase 2.5) — a deterministic seasonal, channel-split generator (`make seed`/
    `make trickle`) with HVE calendar, sparse category, outlier, and signed returns.
  - **Central config** — `TopsalesProperties` binds the whole `topsales.*` tree; the dashboard reads
    `GET /api/v1/config`.
  - **Forecasting engine** (Phase 3) — `Forecaster` impls (seasonal-naive + additive Holt-Winters,
    cold-start dispatch, prediction intervals + confidence); a batch that writes **versioned, ranked**
    serving rows per tenant×window×channel (atomic swap + rollback, channels summed up to `all`); a
    **time-series-CV WAPE/bias** backtest (`make eval`, [report](docs/forecast-eval-report.md)).
  - **Forecast read path** (Phase 4) — `mode=forecast` serves the precomputed serving rows via a
    `ForecastProvider` (`PrecomputedForecastProvider`) behind a **Redis cache-aside** layer (jittered
    TTL, single-flight lease, **full fail-open**), down a **4-tier degradation chain that never fails
    closed** — fresh serving rows → aged last-good past the 36h freshness SLO (`stale`) → in-JVM
    seasonal-naive from actuals (`degraded`) → actuals top-k floor (`pending`); the batch bumps a
    **per-tenant cache version** (`INCR`) on each serving swap to invalidate stale top-k in O(1). The
    dashboard surfaces the **status badge** (fresh/stale/degraded/pending), `asOf`, confidence chips,
    and prediction-interval error bars.
  - **GenAI insight** (Phase 5) — a grounded natural-language line behind the `InsightGenerator` seam.
    The always-on **`TemplateInsightGenerator`** floor builds one deterministic sentence purely from the
    computed top-k figures (no model, no network, never null); a **`GroundingValidator`** rejects any
    output containing a number not derivable from the request, and untrusted category names are fenced as
    data (**prompt-injection-safe**). The insight is generated **lazily inside the Phase-4 forecast cache
    supplier** and cached under the **same per-tenant Redis key** (invalidated by the same batch
    version-bump); `TopKResponse.insight` is populated on both forecast and actuals.
  - Postgres + Flyway via Docker Compose.
- **Designed behind the same interfaces (later phases / `aws` profile):** the cloud insight impl
  (**`BedrockInsightGenerator`** — built but creds-gated; activates only with
  `topsales.insight.provider=bedrock` + AWS credentials, decorating the template floor and degrading back
  to it on timeout/ungrounded), the Python/SageMaker global model + Croston behind the `Forecaster` seam,
  Kinesis/DynamoDB/S3 impls, the React SPA on Vercel, the forecast-vs-actual per-category **time-series
  overlay**, and the 5-stack AWS CDK.

## License

[MIT](LICENSE)
