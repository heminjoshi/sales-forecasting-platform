# Phase 6 — Hardening: resilience & observability

> [WS-B, WS-D] · source `Build-Delivery-Plan-v3.md:123-129`. **Backend hardening, _not_ CDK** — the
> 5-stack CDK is Phase 7. No AWS account, no infra in this phase.

## Objective & acceptance

Make the running system **resilient under injected failure** and **observable**.

**Acceptance (`:129`):** *metrics scrape-able; injected failures (Bedrock down, forecast missing, bad
event) degrade gracefully + observable.*

The four plan tasks (`:124-127`):
1. Idempotency + dedupe; quarantine path for malformed (📐 SQS DLQ in prod).
2. Timeouts/retries/circuit breaker on Bedrock (Resilience4j).
3. Actuator + Micrometer: RED + custom ML-quality metrics (freshness %, WAPE, batch success,
   degraded-read count) — the names the Monitoring stack alarms on.
4. Structured logging (tenant/request ids); finalize `docs/runbook.md`.

## Current state (audited against the repo, not status markers)

- **Task 1 already built** (Phases 2/2.5): `JdbcEventLedger` `INSERT … ON CONFLICT (idempotency_key)
  DO NOTHING` (`JdbcEventLedger.java:41`); `JdbcQuarantineRepository` dead-letters malformed/unknown-tenant
  events; manual validation → quarantine sink (`IngestionService.java:140,173`). **Net-new = metrics
  over these paths, not new plumbing.**
- **Resilience4j absent** everywhere. Bedrock fail-soft = hand-rolled single-attempt `try/catch →
  template` (`BedrockInsightGenerator.java:95-108`), SDK `apiCallTimeout` only — no retry, no breaker.
- **Actuator/Micrometer/Prometheus absent** in all 7 poms; no `management:` block in either
  `application.yml`. `docs/runbook.md` already *names* the metrics but nothing emits them.
- **Structured logging greenfield**: `TenantScopeFilter` uses a request **attribute, not MDC**
  (`:41`); no request-id, no `logback-spring.xml`, no log pattern.

## Decisions locked

| # | Decision | Value / rationale |
|---|---|---|
| Branch | off merged `main` | PR #9 merged (`d049cc1`); `feat/phase6-hardening`, linear history |
| Metrics facade | Micrometer; registry is the swap | instrument once with `MeterRegistry` |
| Local registry | `micrometer-registry-prometheus` → `/actuator/prometheus` | scrape with **no AWS account** (local-runnable principle) |
| Cloud registry | `micrometer-registry-cloudwatch` `@Profile("aws")` | **designed-only**, not active locally; Phase-7 Monitoring stack alarms on the same names. CloudWatch is *a registry*, not an alternative to Micrometer |
| Resilience4j scope | core `circuitbreaker`+`retry` libs, confined to `topsales-insight` | constructed in `BedrockInsightGenerator.create(...)` like `bedrockruntime`; keep off the local api runtime graph |
| Metrics depth | **pragmatic** | API is the scrape surface; batch emits structured-log metrics; live WAPE stays the offline `EvalMain` report (residual) |
| Batch metrics | structured-log `batch_success`/duration; prod push designed-only | ephemeral one-shot JVM — nothing to scrape live |

## Steps

### 1. Foundation (shared/contended files, one sequential pass — may compile)
- `topsales-api/pom.xml`: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`.
- `topsales-insight/pom.xml`: `io.github.resilience4j:resilience4j-circuitbreaker` + `-retry` (core libs).
- `topsales-api/.../application.yml`: `management.endpoints.web.exposure.include: health,info,prometheus,metrics`
  + `management.metrics.tags.application: topsales-api`. (No actuator on the batch.)
- `TopsalesProperties`: add breaker/retry knobs **only if needed** — prefer a couple of fields on the
  existing `Insight` record over a new 7th component (adding a nested record breaks every
  `new TopsalesProperties(` test call-site — grep first, per the Phase-5 gotcha).
- `micrometer-registry-cloudwatch` dep + a `@Profile("aws")` `MetricsConfig` (designed-only) for the swap.

### 2. Parallel streams (disjoint ownership; **no Maven** in these agents)
- **A — Resilience4j on Bedrock** (`topsales-insight`): wrap the single `client.invokeModel(...)`
  (`BedrockInsightGenerator.java:97`) in `CircuitBreaker` + `Retry` via
  `Decorators.ofSupplier(...).withCircuitBreaker(cb).withRetry(retry).get()`; keep the outer
  `try/catch → template.generate(req)` so `CallNotPermittedException`/`ApiCallTimeoutException` fall
  through to the template (fail-soft preserved). Build cb/retry in `create(...)` (`:83`). Defaults:
  retry 2× on timeout/5xx, breaker 50% over a 10-call window, 30s open. Tests mirror the mocked-client
  pattern: retry-then-succeed, breaker-opens → template, timeout → template.
- **B — Metrics** (`topsales-api` service/web + `topsales-forecast` batch): `MetricNames` constants;
  in `ForecastReadService.handle` (`:69`) `counter("topsales.read.total","status",…,"mode",…)` at each
  rung (covers degraded-read + freshness) + count provider faults at the swallow (`:87-93`);
  `topsales.insight.fallback.total`; a `Gauge topsales.forecast.freshness.seconds{tenant}` reading the
  newest serving-row `as_of` age on scrape; RED is free via actuator `http.server.requests`; instrument
  `ForecasterJob.run`/`runTenant` (`:102,154`) + structured `batch_success=… durationMs=… pkWrites=…`.
- **C — Structured logging + runbook + Postman**: MDC in `TenantScopeFilter` (`:41`) —
  `tenantId` + a request id (inbound `X-Request-Id` else `UUID`), echo the id in the response header,
  **clear both in `finally`**; `logback-spring.xml` with `%X{tenantId} %X{requestId}`; per-tenant MDC in
  `ForecasterJob.runTenant`; finalize `docs/runbook.md` to the emitted metric names + the WAPE-live
  residual + the `/actuator/prometheus` how-to; Postman `/actuator/prometheus` scrape assertion + a
  `topsales.read.total{status=degraded}` bump on the wipe scenario.

### 3. Verify (one agent, single Maven invocation)
`mvn -o test-compile` + `make test`. Adversarial: breaker-open **and** timeout both still return the
grounded template (read never blocks); MDC cleared in `finally` (no tenant-id leak across pooled threads
— the security-adjacent correctness point for this phase).

## Acceptance checklist
- [ ] `make test` green (131 + resilience + metrics tests).
- [ ] `curl :8080/actuator/prometheus` shows `http_server_requests…`, `topsales_read_total…`,
      `topsales_forecast_freshness_seconds…`.
- [ ] Bedrock down (`provider=bedrock`, no creds) → template insight renders; breaker opens;
      `topsales_insight_fallback_total` climbs; read never blocks.
- [ ] Wipe serving table → dashboard `degraded`; `topsales_read_total{status="degraded"}` increments.
- [ ] Malformed event → `202 quarantined=1`; logs carry `tenantId`/`requestId`.
- [ ] `public-repo-check` clean.

## Outcome
✅ **Complete & green** (145 tests; live acceptance verified). Hand-off:
[`../handoff-docs/phase-6-handoff.md`](../handoff-docs/phase-6-handoff.md).

## Out of scope / deferred
- Live `wape_rolling` metric (offline `EvalMain` report stands; documented residual).
- Prometheus scrape of the ephemeral batch JVM (prod CloudWatch EMF / Pushgateway — designed).
- SQS DLQ for quarantine (📐 designed-only, `:124`).
- CDK / infra validation, CORS, SPA — **Phase 7**.
