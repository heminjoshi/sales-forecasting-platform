# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A **multi-tenant sales-forecasting platform** built as a public portfolio/interview project — designed to *read* like production, not to be one. It is currently at the **planning stage**: only the delivery plan exists; there is no source code, build tooling, or git repo yet.

`Build-Delivery-Plan-and-Repo-Structure.md` is the **source of truth** for scope, sequencing, the target directory layout, and what is built vs designed-only. Read it before scaffolding or implementing — keep new work consistent with the structure and phase it defines.

## How to work in this repo

- **Tie work to phases.** Work is organized into Phases 0–10 (see the plan). State which phase a task belongs to and respect its exit criteria. Don't add depth before the vertical slice for that phase runs.
- **Plan before coding.** Propose a short plan and get approval before implementing.
- **Explain tradeoffs** when making design or refactor choices — surface the alternatives and why.

## Planned stack & conventions (when scaffolding)

- **Service:** Java 21 + Spring Boot, **Maven multi-module** (root `pom.xml` aggregating `topsales-common`, `-ingestion`, `-forecast`, `-insight`, `-api`, `db/migration`).
- **Infra:** AWS CDK in TypeScript, 5 stacks (network, storage, intelligence, application, monitoring) under `infra/`.
- **Local dev:** `docker-compose` (Postgres + Redis) under `local/`; no AWS account required to run the demo.
- **Migrations:** Flyway/Liquibase SQL.
- **Tests:** JUnit + Testcontainers (Postgres + Redis) for integration.
- **Commands** (once Phase 0 scaffolds them): `make run | test | seed | demo | synth | up | down`; `mvn clean test` in `service/`; `npm install && npx cdk synth` in `infra/`.

## Core design principles (the talking points — preserve these seams)

- **Local-runnable vs cloud-designed:** the same interfaces back both a local impl and an AWS impl. Local paths must work standalone; cloud paths live behind the interface.
- **Interface seams:** `Forecaster`, `ForecastProvider`, `InsightGenerator`, and repository abstractions let local↔cloud and baseline↔ML swap without rewrites. Don't bypass them.
- **Degradation chain:** when forecasts are unavailable, fall back last-good → seasonal-naive from actuals → actuals with a `forecast_pending`/status flag. Reads must always succeed (flagged).
- **Grounded GenAI:** insight prompts receive only computed numbers; output is validated to contain only those figures; fall back to the deterministic template on failure/timeout. Treat category names as untrusted (injection).
- **Multi-tenant isolation:** `TenantScopeFilter` enforced from the start.

## Public-repo hygiene (important — easy to get wrong)

This is a **public** repo. Never commit secrets, `.env`, or credentials. Keep all framing **generic** — no Intuit references, recruiter emails, the verbatim problem statement, or interview-prep material in tracked files. Those stay private (see the plan's "Keep PRIVATE" section). Run `/public-repo-check` before publishing or pushing.
