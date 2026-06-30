# Phase 4 — Forecast serving + resilience

> **Milestone:** FORECAST-READY · **Status:** ✅ done — see [phase-4 handoff](../handoff-docs/phase-4-handoff.md).
> Full execution detail in `~/.claude/plans/come-up-with-a-jazzy-eagle.md` (private).

## Objective & acceptance
Wire `mode=forecast` end-to-end: read precomputed serving rows behind a cache, with a degradation chain
so reads never fail closed, and surface honest freshness to the dashboard.
**Acceptance:** forecast reads from precompute+cache; **forecasts wiped → dashboard still renders**
(degraded, flagged). _(Met live — see handoff.)_

## Current state (entering) → what this phase added
- **Entering (Phase 3):** versioned serving rows written by the batch; `ForecastProvider` an unimplemented
  interface; `mode=forecast` returned the actuals aggregation relabeled `pending`; no Redis.
- **Added:** `PrecomputedForecastProvider`; `ForecastReadService` 4-tier degradation chain
  (fresh→stale→degraded→pending); `SeasonalNaiveFallback` (reuses `SeasonalNaiveForecaster`);
  `RedisCacheShell` (per-tenant version key, jittered TTL, single-flight, fail-open) + batch-driven
  `tenantver` bump; dashboard status badge / confidence chips / interval error bars / degraded banner;
  forecast + wipe-scenario Postman requests.

## Decisions locked (full table in the handoff)
4-tier ladder · freshness SLO 36h · degraded = value + LOW, no interval · cache key adds `{channel}` ·
event-driven version bump · fail-open cache · `topsales-api`→`topsales-forecast` via classifier `app` +
`@ComponentScan` exclude of `com.topsales.forecast.*` · actuals stays cache-bypassed/fresh.

## Out of scope / deferred
- **GenAI insight** (`insight` stays `null`) → Phase 5.
- **AWS profile / DynamoDB ForecastProvider / CDK** → Phase 7 (same seams).
- **True forecast-vs-actual time-series overlay** → designed, deferred (per-day forecast persistence +
  new series endpoint + line/band UI). See handoff "Work done outside the plan".

## Outcome
✅ Built via Wave-0 foundation + 3 parallel subagents; `make test` green (110 tests); all read scenarios
verified on real Postgres + Redis. Handoff: [phase-4-handoff](../handoff-docs/phase-4-handoff.md).
