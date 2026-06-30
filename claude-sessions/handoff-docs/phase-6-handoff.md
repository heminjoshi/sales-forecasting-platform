# Phase 6 — Handoff

> **Status:** ✅ Complete & green — on branch **`feat/phase6-hardening`**, **PR pending** (committed; to be
> pushed + opened against `main`). Covers the **hardening: resilience & observability** layer (WS-B, WS-D):
> Resilience4j on the Bedrock call, Actuator + Micrometer metrics, and structured (tenant/request-id) logging.
> Hand-off for whoever picks up **Phase 7** (UI production path + AWS CDK infra validation).
> **Date:** 2026-06-29 · **Private/gitignored** — may reference employer/interview specifics.

## TL;DR
The running system is now **resilient under injected failure** and **observable**. The single Bedrock
`InvokeModel` call is wrapped in a **Resilience4j** circuit-breaker + retry (still failing soft to the
deterministic template — the read never blocks). `topsales-api` exposes **`/actuator/prometheus`** with RED
(`http.server.requests`) plus custom **ML-quality** meters (read-status, forecast freshness, provider faults,
insight fallbacks). Every log line carries **`tenantId` + `requestId`** via SLF4J MDC (set/cleared in
`TenantScopeFilter`, `X-Request-Id` echoed). **Key audit finding:** idempotency/dedupe/quarantine were
**already built** in Phase 2/2.5 — Phase 6 made them *observable*, it did not re-plumb them. **`make test`
green — 145 tests** (was 131). Built via the **`/implement-phase` skill** driving a deterministic Workflow
(foundation → 3 parallel ownership-partitioned streams → verify).

## References (read these first)
- **This phase's plan doc (durable):** [`../implementation-plan/phase-6-hardening.md`](../implementation-plan/phase-6-hardening.md)
  *(new this phase — Phase 5 had left this gap; the skill now requires it)*.
- **Approved execution plan:** `~/.claude/plans/velvet-snacking-starlight.md`.
- **Delivery plan (source of truth):** `private/Build-Delivery-Plan-v3.md` (Phase 6, `:123-129`) · **Design:**
  `private/Design-Doc-v3-Consolidated.md` (DR-1, DR-6 resilience seams).
- **Public docs synced:** `docs/lld.md` **§15** (new) · `docs/runbook.md` (finalized to real metric names) ·
  `README.md` / `CLAUDE.md` status lines · `test-plan/integration-tests.md §8` + `manual-qa-test.md §6.5`.
- **Implementation-plan index:** [`../implementation-plan/README.md`](../implementation-plan/README.md).
- **Prior handoffs:** [`phase-0`](phase-0-handoff.md) · [`phase-1`](phase-1-handoff.md) ·
  [`phase-2`](phase-2-handoff.md) · [`phase-3`](phase-3-handoff.md) · [`phase-4`](phase-4-handoff.md) ·
  [`phase-5`](phase-5-handoff.md).

## What shipped

### Resilience4j on Bedrock (`topsales-insight`, `topsales-api/config`)
- **`BedrockInsightGenerator`** — the single `client.invokeModel(...)` call is now composed as
  `Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, supplier))` (breaker innermost, retry
  outermost; **no** `Decorators` helper — that lives in `resilience4j-all`, not on the classpath). The
  existing outer `try/catch → template.generate(req)` is preserved: a dedicated `catch (CallNotPermittedException)`
  (breaker open) and the generic `catch (Exception)` (timeout/SDK/malformed) both fall to the template.
  Defaults in `create(...)`: **Retry** `maxAttempts=2` on `ApiCallTimeoutException`/`SdkException` (a grounding
  rejection is a boolean check *outside* the decorated call, so never retried); **CircuitBreaker** count-window
  10, opens at 50% over ≥5 calls, 30s open; both named `"bedrock-insight"`.
- **`onFallback` callback** — a `Runnable` fired **exactly once** on every template-fallback path (exception,
  open-breaker, empty/ungrounded). Null-safe. `InsightWiring` wires it to
  `meterRegistry.counter("topsales.insight.fallback.total").increment()`, keeping Micrometer in the api layer
  and `resilience4j`/AWS SDK confined to `topsales-insight` (the `create(...)` factory is still the single
  construction point).
- Tests (insight 17→20): retry-then-succeed (`invokeModel` ×2), breaker-opens → short-circuits without invoking
  → template, timeout → template, `onFallback` fired once/thrice. Injection-escaping assertion intact.

### Metrics — Actuator + Micrometer (`topsales-api/metrics`, read path, batch)
- **Deps:** `spring-boot-starter-actuator` + `micrometer-registry-prometheus` (api). **`management`** block in
  `topsales-api/application.yml` exposes `health,info,prometheus,metrics` + `application=topsales-api` tag.
- **`MetricNames`** constants; **`ForecastFreshnessGauge`** (`@Component`) — a global gauge =
  seconds since newest serving-row `as_of`, via the **new `ServingTableRepository.newestAsOf()`** seam
  (impl in `JdbcServingTableRepository`); returns **NaN** when the serving table is empty (no misleading 0).
- **`ForecastReadService.handle`** — split into `handle`→`resolve`; counts **once** from the final status:
  `topsales.read.total{status,mode}`. The fail-soft swallow increments `topsales.forecast.provider.faults.total`.
- **`ForecasterJob`** (forecast batch) — emits a structured `batch metrics: batch_success=… tenants=… durationMs=…
  pkWrites=…` log line (no micrometer dep added to the batch — ephemeral JVM); per-tenant `tenantId` MDC.
- Tests: `ForecastFreshnessGaugeTest` (empty→NaN), `ForecastReadServiceTest` counter assertions (+ SimpleMeterRegistry).

### Structured logging (`topsales-api/web`, resources)
- **`TenantScopeFilter`** — MDC `tenantId` (when header present) + `requestId` (inbound `X-Request-Id` else a
  generated UUID, echoed on the response header); **both cleared in a `finally`** around `chain.doFilter` so a
  tenant id can't leak across pooled servlet threads. **`logback-spring.xml`** pattern carries
  `[%X{tenantId:-} %X{requestId:-}]`; the forecast `application.yml` mirrors the pattern for the batch.

### Docs, Postman, test-plan
- **`docs/runbook.md`** finalized to the emitted metric names (RED→`http.server.requests`, degraded→
  `topsales.read.total{status="degraded"}`, freshness gauge, fallbacks) + a scrape how-to + the WAPE-live residual.
- **`docs/lld.md §15`** (new) documents the whole layer. **README/CLAUDE.md** status → Phase 6.
- **Postman** — `/actuator/prometheus` scrape assertions + a `status="degraded"` sample check on the wipe scenario.
- **`test-plan/`** — `[P6]` cases: integration §8 (resilience/metrics/logging), manual-qa §6.5; README phase-gating
  already P6-aware; canary `CN-11` already P6-aware.

## Locked decisions (carry forward)
| # | Decision | Value |
|---|---|---|
| Metrics facade | Micrometer; the **registry** is the swap | instrument once with `MeterRegistry` |
| Local registry | `micrometer-registry-prometheus` → `/actuator/prometheus` | scrape with **no AWS account** |
| Cloud registry | `micrometer-registry-cloudwatch` is **documentation-only** in P6 | NOT added as a dep — adding it would make Spring Boot autoconfigure a CloudWatch client without creds and risk a startup failure; the Monitoring CDK stack is Phase 7 |
| Resilience4j | core `circuitbreaker`+`retry` libs, confined to `topsales-insight` | composed by hand (no `Decorators`); fail-soft preserved |
| Read counter | one emission point from final status | `topsales.read.total{status,mode}`; covers degraded-read + freshness |
| Batch metrics | structured-log line, **no** micrometer dep on the batch | prod pushes via CloudWatch EMF / Pushgateway (designed) |
| WAPE live | stays the **offline** `make eval` report | live `wape_rolling` is a documented residual |
| MDC safety | clear in `finally` | no tenant-id leak across pooled threads (the phase's security talking point) |

## How to build / run / verify
```bash
make test         # full reactor unit tests, no Docker — green (145 tests)
make up           # Postgres + Redis
make seed         # seasonal channel-split history (t_demo + t_acme)
make forecast     # batch: serving rows + tenantver bump + the structured batch_metrics log line
make run          # api on :8080
```
**Live acceptance (all verified this session):**
- `curl :8080/actuator/prometheus` → `http_server_requests…`, `topsales_read_total{mode="forecast",status="fresh"}`,
  `topsales_forecast_freshness_seconds`.
- **Forecast missing (observable degraded):** `TRUNCATE serving_rows, serving_active_version;` **+ `redis-cli FLUSHALL`**
  (the read is cache-aside — must flush to bypass the cached top-k), then a forecast read → `status=degraded` (200),
  `topsales_read_total{status="degraded"}=1`, and `topsales_forecast_freshness_seconds=NaN`. Re-run `make forecast`
  to restore → gauge returns to a real number.
- **Bad event:** `POST /api/v1/events` with `amount: 12.345` → `202 {received:1,quarantined:1}` (already-built path).
- **Structured logging:** response `X-Request-Id` echoes an inbound id; batch logs show populated `[t_demo ]`/`[t_acme ]`
  brackets (per-tenant MDC). On happy-path *reads* nothing logs mid-request, so the bracket is empty `[ ]` there —
  that's the pattern working, not a bug.
- **Bedrock resilience** is unit-tested (no live AWS); under `provider=bedrock` with no creds it falls back to the
  template every call.

## Gotchas / non-obvious
- **`micrometer-registry-cloudwatch` is deliberately NOT a dependency.** Documentation-only swap (runbook + a yml
  comment). Adding it risks a no-creds startup failure. Revisit in Phase 7 with the Monitoring stack.
- **`topsales.read.total` covers `mode=forecast` only.** Standalone `mode=actuals` reads go through
  `ActualsService` via the controller, **not** `ForecastReadService.handle`, so they're observed via
  `http.server.requests` (RED), not the custom counter. The `mode` tag is therefore always `forecast` today —
  intentional, documented in lld §15. (If you later route actuals through a unified path, the tag populates.)
- **Degraded-read demo needs a Redis FLUSHALL**, not just a serving-table truncate — the cached top-k would
  otherwise serve a stale `fresh` response (cache-aside). Same trap as the Phase-4 degradation demo.
- **One verify fix:** the test fake `CapturingServingTable` (in `ForecasterJobTest`) needed a `newestAsOf()`
  override after the port gained that method — the "added an abstract method, a test fake didn't follow" class.
  If you add to `ServingTableRepository`, grep for its implementors (prod `JdbcServingTableRepository` + that fake).
- **Resilience4j 2.3.0** core libs need an **explicit version** (not in the Spring Boot BOM). `Decorators` is in
  `resilience4j-all` (not added) — compose with `CircuitBreaker.decorateSupplier` / `Retry.decorateSupplier`.
- **Carried Spring Boot 4.1 + Testcontainers traps still apply** (Phase 2–5): `spring-boot-flyway`; standalone
  MockMvc only; `*IT`s CI-only on this host.

## Git / PR state
- Branch **`feat/phase6-hardening`** off `main` (`d049cc1`, which carries merged Phase 5 via PR #9).
- Commits (sliced, on the branch):
  - `a0d19c0` feat(phase6): Resilience4j circuit-breaker + retry on the Bedrock call
  - `4dfb860` feat(phase6): Actuator + Micrometer metrics — RED + ML-quality meters
  - `24fb713` feat(phase6): structured logging — tenant + request id via MDC
  - `333a001` test(phase6): Postman /actuator/prometheus scrape + degraded-metric assertions
  - `52375dc` docs(phase6): sync README/CLAUDE.md/lld/runbook + P6 test-plan cases
  - `14f9378` chore(skills): implement-phase persists the durable phase plan doc + test-plan cases
- **PR:** to be pushed + opened against `main`. `public-repo-check` passed (no private dirs tracked, no employer
  term — "intuit" hits are the word *intuitive*; no secrets; no hardcoded AWS creds — Bedrock uses the default chain).

## Carried forward from prior phases (reconciliation)
- ✅ **Merge the Phase-5 PR (#9)** (Phase-5 open item) — **done**: merged to `main` (`d049cc1`) before this branch was cut.
- ✅ **Phase 6 idempotency/dedupe/quarantine task** — found **already built** in Phase 2/2.5 (audit); satisfied by
  making it observable rather than re-plumbing. No SQS DLQ (📐 designed-only, unchanged).
- ✅ **`docs/runbook.md` metric names finalized** — the runbook previously *named* Phase-6 metrics aspirationally;
  now matched to what's emitted.
- **Still open from prior phases (unchanged):** optional close of the **numbers-only insight-validation residual**;
  optional forecast/cache + insight **Testcontainers ITs** (CI-only here); optional populate forecast `windowFrom/To`;
  optional time-series-overlay build; off-repo recruiter reply (WS-G); `make demo` (newman) is a Phase-8 placeholder.
- **Earlier acceptance re-verified:** `make test` green across all modules (now 145); Phase-4 degradation chain +
  Redis cache untouched and re-exercised live; Phase-5 grounded/injection-safe insight unchanged. No regressions.

## Next phase — Phase 7: UI production path & infra validation [WS-C, WS-D]
The actual **AWS CDK** phase: `infra/` 5-stack CDK (Network/Storage/Intelligence/Application/Monitoring) →
`npm install && npx cdk synth` clean (fix Bedrock endpoint enum + `CfnGuardrail` drift); validate the Intelligence
stack (Bedrock policy + **guardrail** + model-config SSM + SageMaker registry/role) and Network endpoints; React SPA
→ **Vercel** (`web/vercel.json`) **or** keep the static dashboard; **API CORS** allow-list (localhost + Vercel);
`infra/test/` CDK assertion tests; `docker/` Dockerfiles → ECR + git-sha `imageTag`; CI `docker.yml`.
**The Monitoring stack alarms on the Phase-6 metric names shipped here** — keep them stable.
- **First concrete step:** `/implement-phase 7` (note: `infra/` and `web/` are currently empty `.gitkeep` only).

## Open items / decisions pending
- [ ] **Push `feat/phase6-hardening` + open the PR** to `main`.
- [ ] Optional: instrument `mode=actuals` reads into `topsales.read.total` if a unified read path is desired.
- [ ] Optional: forecast/cache + insight **Testcontainers IT** (CI-only on this host).
- [ ] Optional: close the numbers-only insight-validation residual; populate forecast `windowFrom/To`; time-series overlay.
- [ ] Off-repo (WS-G, carried): recruiter reply.
- [ ] Phase 8: wire `make demo` (newman) over the forecast + injection + new actuator Postman requests.

## ⭐ Work done outside the plan / repo
- **Updated the `/implement-phase` skill** (tracked, committed `14f9378`) to **persist the approved plan as a durable
  `claude-sessions/implementation-plan/phase-<N>-*.md`** (§3) and wire the index (§6) — closing the gap Phase 5 left
  (`_(approved exec plan)_`). The skill was *also* updated (by the user/linter mid-session) to require authoring
  **`test-plan/` `[P<N>]` cases** as part of planning — I authored the Phase-6 cases accordingly. **This skill change
  was the user's explicit ask this session.**
- **Authored the durable `phase-6-hardening.md` plan doc** + flipped the index row (was `_todo_`).
- **Refined the CloudWatch decision** vs the approved plan: the plan said "add the `micrometer-registry-cloudwatch`
  dep + `@Profile(aws)` config (designed)"; I **downgraded it to documentation-only** (no dep) to avoid a no-creds
  startup-autoconfig failure. Noted in the plan doc + runbook + lld §15.
- **Dog-fooded the skill on Phase 6** via the `Workflow` tool (user chose deterministic workflow): 1 foundation agent
  → 3 parallel streams (A resilience, B metrics, C logging+docs+postman) → 1 verify agent (single `mvn` + `make test`
  + adversarial review). Workflow run id `wf_e1be9e8c-bdd`.
- **Branch hygiene:** confirmed PR #9 already merged to `main`; cut the phase-6 branch off the updated `main`.
- **Off-repo:** none.
