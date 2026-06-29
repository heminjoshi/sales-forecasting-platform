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
- **[P7]** — production path: API **CORS allow-list** (`localhost` + the Vercel origin) on `/api/**`; the
  `web/` React SPA (Vite/Recharts) cross-origin **prod build** deployed to Vercel (the static dashboard
  stays the local demo); and (PR2) the AWS **CDK infra** validation. Because CORS is **browser-enforced**,
  its header behavior is realized via **Postman** (Origin/preflight probe) + **manual/canary** checks, **not**
  HTTP-slice ITs (the SB4.1/Jackson-3 runner caveat — no `@WebMvcTest`/`TestRestTemplate`); the one automated
  JUnit case is a config-binding **unit** test over the allow-list property.

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
