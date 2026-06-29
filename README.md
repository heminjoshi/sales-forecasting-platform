# Sales-Forecasting Platform

[![ci](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml)

> Multi-tenant "top sales by category" platform — ingests sale events, maintains per-tenant
> category aggregates, forecasts each category forward, ranks to a top-k, surfaces a grounded
> natural-language insight, and presents it in a dashboard. Java / Spring Boot, runnable locally
> with one `docker compose up`; AWS path designed in CDK behind the same interfaces.

 > **Status:** 🟢 Walking skeleton runnable (Phase 2). End-to-end **actuals** path works:
> ingest events → idempotent per-tenant category aggregates → ranked top-k over the REST API →
> served dashboard. Synthetic seasonal data + the channel dimension (Phase 2.5), forecasting
> (Phase 3), forecast serving + degradation + cache (Phase 4), and the GenAI insight layer
> (Phase 5) land next.

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

For a realistic demo, `make seed` bulk-backfills months of seasonal, channel-split history, and
`make trickle` posts live events that continue it so the dashboard moves (see `data/seed/`). The
dashboard's **tenant** picker is populated from `GET /api/v1/tenants`; two demo tenants (`t_demo`,
`t_acme`) are seeded with independent data, so you can flip between them to see multi-tenant isolation.

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

- **Built & runnable now (Phase 2):** event ingestion with idempotent additive aggregation,
  tenant-local bucketing, dedupe + raw log + quarantine; the two-mode read API (actuals served;
  `forecast` returns the `pending` floor until Phase 3); `TenantScopeFilter` multi-tenant isolation;
  RFC 7807 errors; the served dashboard; Postgres + Flyway via Docker Compose.
- **Designed behind the same interfaces (later phases / `aws` profile):** the `channel`
  (`ONLINE`/`OFFLINE`) first-class dimension + seasonal synthetic-data generator (Phase 2.5;
  [ADR-0010](docs/adr/0010-channel-as-first-class-dimension.md)), the forecasting engine and
  versioned serving table (`Forecaster`/`ForecastProvider`), the full degradation chain + Redis cache,
  the grounded GenAI insight layer (`InsightGenerator` → Bedrock), Kinesis/DynamoDB/S3/SageMaker
  impls, the React SPA on Vercel, and the 5-stack AWS CDK.

## License

[MIT](LICENSE)
