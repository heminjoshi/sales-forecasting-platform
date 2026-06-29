# Test Plans

Test strategy for the multi-tenant sales-forecasting platform, split by test type. Each plan lists
happy paths **and** the edge / corner / error cases — and, where an error is the *correct* outcome,
documents the exact expected status and RFC 7807 problem.

| Plan | Scope | Runs where |
|---|---|---|
| [integration-tests.md](integration-tests.md) | Component + API behavior against a real Postgres/Redis (Testcontainers) | CI + local |
| [load-tests.md](load-tests.md) | Throughput / latency under **expected** load vs SLOs | Staging / perf env |
| [stress-tests.md](stress-tests.md) | Behavior **beyond** capacity: saturation, exhaustion, recovery | Perf env (isolated) |
| [canary-tests.md](canary-tests.md) | Post-deploy synthetic probes + progressive-rollout guardrails | Staging / prod |
| [manual-qa-test.md](manual-qa-test.md) | Scripted + exploratory human testing of the demo + dashboard | Local / staging |

## Phase gating (what is testable today)

The platform is built phase-by-phase ([`../docs/`](../docs/), Build-Delivery-Plan). Cases are tagged:

- **[P2]** — built now (walking skeleton): ingestion, aggregation, actuals read, tenant isolation, errors.
- **[P2.5]** — channel dimension + synthetic seasonal data (ADR-0010).
- **[P3]** — forecasting engine (Holt-Winters / seasonal-naive, WAPE eval).
- **[P4]** — forecast serving, degradation chain, Redis cache.
- **[P5]** — GenAI insight layer (grounding, injection safety).
- **[P6]** — hardening: resilience & observability (health/readiness, metrics, structured logs).
- **[P7]** — production path & infra: API **CORS allow-list** (`localhost` + the Vercel origin) on `/api/**` and the `web/` React SPA (Vite/Recharts) cross-origin **prod build** deployed to Vercel (the static dashboard stays the local demo); plus the synth-only **AWS CDK** 5-stack — ingress via **API Gateway → private ALB** (compute never internet-facing), least-privilege Bedrock IAM, non-root containers, and the metric-name contract gate. Because CORS is **browser-enforced**, its header behavior is realized via **Postman** (Origin/preflight probe) + **manual/canary** checks, **not** HTTP-slice ITs (the SB4.1/Jackson-3 runner caveat — no `@WebMvcTest`/`TestRestTemplate`); the one automated JUnit case is a config-binding **unit** test over the allow-list property. Infra cases (`IT-IF`) are `aws-cdk-lib` assertion tests under `infra/test/` (run by `npm test` / `infra.yml`), not Maven.
- **[P8]** — test-hardening & Postman gate: promotes the prior deferred `⚠️/❌` rows to **real full-stack `*IT`s** (booted against CI-provided Postgres + Redis services — see `ci.yml`; locally the `make up` compose stack) — `RedisCacheShellIT` (real-Redis miss→hit / event-driven invalidation / single-flight: `IT-CA-01/03/05`, and the lazy+cached insight path `IT-AI-05`), `ForecastDegradationIT` (HTTP `mode=forecast` fresh-after-batch + serving-wiped degradation: `IT-FC-02/07`), and `TenantIsolationIT` (end-to-end cross-tenant 403 / unknown-tenant 404 RFC-7807: `IT-RD-30/31/32`). Adds the **Postman coverage gate** — the full `postman/` collection (incl. a new **multi-tenant isolation** folder) run by **Newman** against a live local stack via `make demo`, enforced in CI by `.github/workflows/postman.yml` (Postgres + Redis services → build → seed → boot → poll health → `newman run`, non-zero fails the build). Carried forward (documented, not built): `IT-FC-06` WAPE-on-committed-seed in CI and `IT-FC-03` concurrent mid-batch swap.
- **[P9]** — presentation & rehearsal: no new automated tests (a docs/deck phase); adds the **rehearsal gate** as manual-QA §10 (`MQ-90..96`) — cold-start run-through, the live degradation beat, the now-**fully-offline** demo (Chart.js vendored into `static/vendor/`, so MQ-63/94 drop the last CDN dependency), and "run to time" against `demo-script.md`. The acceptance is a clean cold demo + a 60-min run-through, exercised by these manual cases.

A `[P3+]` case is written now but **expected to be skipped/`@Disabled`** until that phase lands; the
plan is the spec the phase must satisfy. These plans are authored and kept current phase-by-phase by
the `implement-phase` skill — each phase adds its `[P<N>]`-tagged cases here as part of planning.

## Conventions

- **Tenant auth (P2):** the caller's tenant is the `X-Tenant-Id` header. The path `{tenantId}` must
  match it or the read returns **403**. (Bearer-JWT is the designed `aws` evolution; the header is the
  local stand-in.)
- **Reads never fail closed:** a missing/stale/degraded forecast is a `200` with a `status` of
  `fresh|stale|pending|degraded` + `asOf` — **never** a 5xx. Tests assert the body+status, not failure.
- **Ingestion never rejects a bad row mid-batch:** malformed events are *quarantined* and counted, not
  returned as 4xx. The only ingest 4xx is a missing `X-Tenant-Id` (400) or an unparseable top-level body.
- **Error model:** RFC 7807 `ProblemDetail` — assert `status`, `type` (`https://topsales/errors/{slug}`),
  `title`, and `instance` (= request URI).
- **Test data:** seeded tenant `t_demo` (`America/Los_Angeles`, `USD`). Synthetic/canary tenants use a
  `t_canary*` prefix and are cleaned up.
</content>
