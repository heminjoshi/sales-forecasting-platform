# Implementation Plan — Multi-Tenant Sales-Forecasting Platform

Working docs that turn **`private/Build-Delivery-Plan-v3.md`** (what to build, in what order) and the **consolidated design doc** (`private/Design-Doc-v3-Consolidated.md`, supersedes HLD v2) (the architecture) into a concrete, step-by-step build approach — one doc per phase.

> **Private & gitignored.** This whole tree lives under `claude-sessions/` and never goes to GitHub. It may reference employer/interview specifics freely. The *public* repo artifacts (code, `docs/`, README) stay generic — see `/public-repo-check`.

## How to use these docs

Each phase doc follows the same shape:
- **Objective & acceptance** — copied from the plan so the bar is unambiguous.
- **Current state** — what already exists in the repo vs. what this phase adds.
- **Decisions to lock** — open choices the plan left implicit, with a recommendation + rationale (calibrated for "learning the stack" — tradeoffs explained).
- **Steps** — ordered, each with *what / why / concrete commands or file contents / how to verify*.
- **Acceptance checklist** — the gate to call the phase done.
- **Out of scope / deferred** — what intentionally waits for a later phase.

Work top-to-bottom within a phase. Don't add depth before that phase's vertical slice runs (guiding principle #1).

## Phase index

| Phase | Title | Milestone | Plan doc | Handoff | Status |
|---|---|---|---|---|---|
| 0 | Foundations & logistics | — | [phase-0-foundations.md](phase-0-foundations.md) | [phase-0-handoff](../handoff-docs/phase-0-handoff.md) | ✅ done |
| 1 | Design artifacts (LLD, ADRs, OpenAPI, diagrams) | — | [phase-1-design.md](phase-1-design.md) | [phase-1-handoff](../handoff-docs/phase-1-handoff.md) | ✅ done |
| 2 | Walking skeleton (vertical slice + minimal UI) | ⭐ DEMO | [phase-2-walking-skeleton.md](phase-2-walking-skeleton.md) | [phase-2-handoff](../handoff-docs/phase-2-handoff.md) | ✅ done |
| 2.5 | Synthetic data + channel dimension | — | [phase-2.5-channel-and-synthetic-data.md](phase-2.5-channel-and-synthetic-data.md) | [phase-3-handoff](../handoff-docs/phase-3-handoff.md) | ✅ done |
| 3 | Forecasting engine | — | [phase-3-forecasting-engine.md](phase-3-forecasting-engine.md) | [phase-3-handoff](../handoff-docs/phase-3-handoff.md) | ✅ done |
| 4 | Forecast serving + resilience | FORECAST-READY | [phase-4-forecast-serving.md](phase-4-forecast-serving.md) | [phase-4-handoff](../handoff-docs/phase-4-handoff.md) | ✅ done |
| 5 | GenAI insight layer | AI-READY | [phase-5-genai-insight.md](phase-5-genai-insight.md) | [phase-5-handoff](../handoff-docs/phase-5-handoff.md) | ✅ done |
| 6 | Hardening: resilience & observability | — | [phase-6-hardening.md](phase-6-hardening.md) | [phase-6-handoff](../handoff-docs/phase-6-handoff.md) | ✅ done |
| 7 | UI production path & infra validation | — | [phase-7-ui-and-infra.md](phase-7-ui-and-infra.md) | [phase-7-handoff](../handoff-docs/phase-7-handoff.md) | ✅ done (PRs #13 + #14 merged) |
| 8 | Data, tests, Postman | PROD-SHAPED | [phase-8-data-tests-postman.md](phase-8-data-tests-postman.md) | [phase-8-handoff](../handoff-docs/phase-8-handoff.md) | ✅ done (PR open) |
| 9 | Presentation & rehearsal | 🎤 PRESENTATION | [phase-9-presentation.md](phase-9-presentation.md) | [phase-9-handoff](../handoff-docs/phase-9-handoff.md) | ✅ done (PR #17 merged) |
| 10 | Public-repo polish & final dry run | PUBLIC | [phase-10-public-release.md](phase-10-public-release.md) | [phase-10-handoff](../handoff-docs/phase-10-handoff.md) | ✅ done (public + v1.0; PR #19 + doc-sync PR) |

> **Handoff docs** live in [`../handoff-docs/`](../handoff-docs/) — one per completed phase, written for whoever picks up the next phase (state, decisions, how-to-run, gotchas, open items).

**Tight-on-time order** (from the plan): 0 → 1(LLD) → 2 → 2.5 → 3 → 4 → 5 → 9 first (demoable system + deck → passes the interview), then backfill 6 → 7 → 8 → 10.

## Cross-phase conventions (locked once, applied everywhere)

These are decided in Phase 0 and referenced by later phases:
- **Language/build:** Java 21 target (local JDK 26 runs it; CI on Temurin 21), **Spring Boot 4.1.0**, Maven multi-module, base package `com.topsales`.
- **Repo:** private GitHub repo `heminjoshi/sales-forecasting-platform` (created Phase 0); flips public in Phase 10.
- **Module names:** `topsales-common`, `topsales-ingestion`, `topsales-forecast`, `topsales-insight`, `topsales-api`.
- **Profiles:** Spring `local` (default) vs `aws` select impls behind interfaces.
- **Local stack:** Postgres 16 + Redis 7 via `local/docker-compose.yml`.
- **Demo UI:** static assets served from `topsales-api/src/main/resources/static/` (no Node build).
- **Interface seams:** `Forecaster`, `ForecastProvider`, `InsightGenerator`, repository ports — defined in `topsales-common`.
