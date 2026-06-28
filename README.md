# Sales-Forecasting Platform

[![ci](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml/badge.svg)](https://github.com/heminjoshi/sales-forecasting-platform/actions/workflows/ci.yml)

> Multi-tenant "top sales by category" platform — ingests sale events, maintains per-tenant
> category aggregates, forecasts each category forward, ranks to a top-k, surfaces a grounded
> natural-language insight, and presents it in a dashboard. Java / Spring Boot, runnable locally
> with one `docker compose up`; AWS path designed in CDK behind the same interfaces.

<!-- CI badge added after the first green run -->

> **Status:** 🚧 Early scaffolding (Phase 0). The walking skeleton, forecasting, GenAI insight,
> and dashboard land in later phases.

## Quick start (target: run in 2 commands)

```bash
make up      # start Postgres + Redis locally
make run     # start the API on http://localhost:8080
```

Prerequisites: Docker Desktop, JDK 21+, Maven, a browser. No Node, no AWS account required.

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

_Design docs (HLD, LLD, ADRs, OpenAPI, diagrams) — to be added under `docs/`._

## Built vs. designed

_What's runnable vs. what's designed-behind-interfaces — to be summarized._

## License

[MIT](LICENSE)
