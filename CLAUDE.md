# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A **multi-tenant "top sales by category" platform** built as a public portfolio/interview project — designed to *read* like production, not to be one. **Built through Phase 5** (of Phases 0–10): the Maven multi-module service runs locally end-to-end — ingestion → per-tenant/per-channel aggregates → read API + dashboard (actuals), plus the channel dimension + synthetic-data generator (Phase 2.5), a central config surface (`TopsalesProperties`), the forecasting engine (Phase 3: `Forecaster` impls, the batch writing versioned serving rows, the WAPE backtest), the forecast **read path** (Phase 4: `ForecastProvider` over precomputed serving rows, a 4-tier degradation chain that never fails closed — `fresh`→`stale`→`degraded`→`pending` — a Redis cache-aside layer with per-tenant version-bump invalidation and full fail-open, and dashboard status/interval surfacing), and the grounded **GenAI insight layer** (Phase 5: an always-on deterministic `TemplateInsightGenerator` floor + a designed `BedrockInsightGenerator` behind the same `InsightGenerator` seam, `GroundingValidator` rejecting any non-derivable number, prompt-injection-safe handling of untrusted category names, lazy+cached inside the Phase-4 Redis top-k key). Next is the AWS CDK + productionization (Phases 6–8).

Two docs under `private/` (gitignored, never committed) are the **source of truth** — read them before scaffolding or implementing, and keep new work consistent with the structure and phase they define:
- the **delivery plan** (`private/Build-Delivery-Plan-v3.md`) — scope, phase sequencing (Phases 0–10, organized into workstreams WS-A…WS-G), target directory layout, built-vs-designed-only. This v3 supersedes `Build-Delivery-Plan-v2.md` (and the older `Build-Delivery-Plan-and-Repo-Structure.md`) in the same folder; v3 syncs ADRs to the 9 explicit decision records and elevates comparative trade-offs as a first-class, scored deliverable.
- the **consolidated design doc** (`private/Design-Doc-v3-Consolidated.md`) — the architecture: four tiers (presentation → serving → forecast batch → ingestion), interface seams, decision records, the per-component deep-dive, the connection/data-flow catalog, and the canonical data shapes (`SaleEvent` → `AggregateRow` → `ForecastRow`/serving row → `TopKResponse`). v3 supersedes the earlier HLD v2 and the standalone component deep-dive.

## How to work in this repo

- **Tie work to phases.** Work is organized into Phases 0–10 (see the plan). State which phase a task belongs to and respect its exit criteria. Don't add depth before the vertical slice for that phase runs.
- **Plan before coding.** Propose a short plan and get approval before implementing.
- **Explain tradeoffs** when making design or refactor choices — surface the alternatives and why.

## Planned stack & conventions (when scaffolding)

- **Service:** Java 21 + Spring Boot, **Maven multi-module** (root `pom.xml` aggregating `topsales-common`, `-ingestion`, `-forecast`, `-insight`, `-api`, `db/migration`).
- **UI (presentation tier):** the demo dashboard is **static HTML + vanilla JS + Chart.js via CDN**, served from Spring Boot static resources (the API module's `src/main/resources/static/`) — **no Node toolchain, no build step for the demo UI**. This local-served dashboard is what the live demo runs (no internet dependency). The production React SPA (Vite + Recharts) lives in a separate top-level `web/` dir and **deploys to Vercel** (git-push deploys + preview URLs, via `web/vercel.json`); it's **designed-only / optional build** — don't build it for the demo. **S3 + CloudFront is the documented AWS-native alternative, not built.** The dashboard is a thin, read-only view over the REST API; keep business logic out of it.
- **CORS:** because the prod SPA is hosted on Vercel (cross-origin, outside AWS), the API must **allow-list the UI origin** — `localhost` (local) + the Vercel domain (prod). (S3+CloudFront would be same-origin and skip CORS — that's the tradeoff.)
- **Infra:** AWS CDK in TypeScript, 5 stacks (network, storage, intelligence, application, monitoring) under `infra/`. **No CDK hosting resources for the SPA** — it deploys to Vercel, outside the AWS account; the API stack just allow-lists the Vercel origin via CORS.
- **Profiles:** a Spring profile selects impls behind the same interfaces — `local` (Postgres / Redis / filesystem raw log / template insight / Java forecaster / static dashboard) vs `aws` (Kinesis / DynamoDB / S3 / Bedrock / SageMaker / React SPA). Don't hardcode cloud services on the common path.
- **Migrations:** Flyway/Liquibase SQL.
- **Tests:** JUnit + Testcontainers (Postgres + Redis) for integration.
- **Local dev:** prerequisites are **Docker Desktop, JDK 21, Maven, and a browser — no Node, no AWS account.** Run sequence: `docker-compose up` → `make run` → `make seed` → open the dashboard.
- **Commands** (once Phase 0 scaffolds them): `make run | test | seed | demo | synth | up | down`; `mvn clean test` in `service/`; `npm install && npx cdk synth` in `infra/` (CDK is the *only* place Node is used).

## Core design principles (the talking points — preserve these seams)

- **Local-runnable vs cloud-designed:** the same interfaces back both a local impl and an AWS impl. Local paths must work standalone; cloud paths live behind the interface.
- **Interface seams:** `Forecaster`, `ForecastProvider`, `InsightGenerator`, and repository abstractions let local↔cloud and baseline↔ML swap without rewrites. Don't bypass them.
- **Degradation chain:** when forecasts are unavailable, fall back last-good → seasonal-naive from actuals → actuals with a `forecast_pending`/status flag. Reads must always succeed (flagged); the read path survives a total ML-plane outage and the dashboard always renders something honest (status badge: `fresh|stale|pending|degraded` + as-of).
- **Grounded GenAI:** insight prompts receive only computed numbers; output is validated to contain only those figures; fall back to the deterministic template on failure/timeout. Treat category names as untrusted (injection).
- **Multi-tenant isolation:** `TenantScopeFilter` enforced from the start.

## Public-repo hygiene (important — easy to get wrong)

This is a **public** repo. Never commit secrets, `.env`, or credentials. Keep all framing **generic** — no employer/company name, recruiter emails, the verbatim problem statement, or interview-prep material in tracked files (this applies to `CLAUDE.md`, `.gitignore`, and skills too — they're tracked and public). Everything under `private/`, plus `claude-sessions/` and `interview-prep/` (raw transcript exports, recruiter emails, probe bank, STAR stories — all contain employer/interview material), stays **gitignored and never committed**. Run `/public-repo-check` before publishing or pushing.
