# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A **multi-tenant "top sales by category" platform** built as a public portfolio/interview project — designed to *read* like production, not to be one. It is currently at the **planning stage**: only design/plan docs exist; there is no source code or build tooling yet.

Two docs under `private/` (gitignored, never committed) are the **source of truth** — read them before scaffolding or implementing, and keep new work consistent with the structure and phase they define:
- the **delivery plan** (`private/Build-Delivery-Plan-and-Repo-Structure.md`) — scope, phase sequencing, target directory layout, built-vs-designed-only.
- the **HLD v2 design doc** (in `private/`) — the architecture: four tiers (presentation → serving → forecast batch → ingestion), interface seams, decision records. v2 adds the user-facing dashboard tier.

## How to work in this repo

- **Tie work to phases.** Work is organized into Phases 0–10 (see the plan). State which phase a task belongs to and respect its exit criteria. Don't add depth before the vertical slice for that phase runs.
- **Plan before coding.** Propose a short plan and get approval before implementing.
- **Explain tradeoffs** when making design or refactor choices — surface the alternatives and why.

## Planned stack & conventions (when scaffolding)

- **Service:** Java 21 + Spring Boot, **Maven multi-module** (root `pom.xml` aggregating `topsales-common`, `-ingestion`, `-forecast`, `-insight`, `-api`, `db/migration`).
- **UI (presentation tier):** the demo dashboard is **static HTML + vanilla JS + Chart.js via CDN**, served from Spring Boot static resources — **no Node toolchain, no build step for the demo UI**. The production React SPA (Vite + Recharts on S3+CloudFront) is **designed-only** — don't build it for the demo. The dashboard is a thin, read-only view over the REST API; keep business logic out of it.
- **Infra:** AWS CDK in TypeScript, 5 stacks (network, storage, intelligence, application, monitoring) under `infra/`. The Application/Storage stacks also carry the S3 site bucket + CloudFront for the designed React SPA.
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

This is a **public** repo. Never commit secrets, `.env`, or credentials. Keep all framing **generic** — no employer/company name, recruiter emails, the verbatim problem statement, or interview-prep material in tracked files (this applies to `CLAUDE.md`, `.gitignore`, and skills too — they're tracked and public). Everything under `private/` (the delivery plan, the HLD v2 doc, reference material) stays **gitignored and never committed**. Run `/public-repo-check` before publishing or pushing.
