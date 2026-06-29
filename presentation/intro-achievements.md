# Intro & Achievements

The opening (~5 min) and the achievements walk (~10 min) that precede the technical deck.
Generic, project-focused — no employer/interview-specific framing (see `README.md`).

---

## Intro (5 min)

**One-liner.** A multi-tenant "top sales by category" platform: ingest sale/return events →
maintain per-tenant category aggregates → forecast each category forward → rank to top-k → surface a
grounded natural-language insight → present in a dashboard. Built to *read* like production.

**The shape.** `forecasting engine → top-k read model → presentation tier`, over four tiers
(Presentation · Ingestion · Forecast batch · Serving) that couple **only** through a versioned serving
table — no synchronous ML on the read path.

**What's real.** The whole thing runs locally end-to-end in Java / Spring Boot with
`docker-compose up → make run → make seed → make forecast` — no AWS account, no Node, no internet.
The cloud/ML paths (Kinesis, DynamoDB, SageMaker, Bedrock, multi-region) are *designed* behind the
same interfaces and validated by `cdk synth` + assertion tests.

**The through-line I want to land:** every consequential choice is **availability-first** — the read
path survives a total ML-plane outage and always renders something honest. Everything else (precompute,
the degradation chain, the grounded-insight floor, the interface seams) follows from that.

**HLD diagram for the intro:** `docs/diagrams/architecture.md` (the four-tier flow) —
or the architecture slide in `deck/deck.md` ("Architecture — four tiers").

---

## Achievements (10 min) — two quantified stories

### Story 1 — A forecast that's *earned*, with a fallback that's the same code

**Situation.** Forecasting top categories per tenant, where many categories are long-tail (near-zero
sales on most days) and availability is the #1 NFR.

**What I did.**
- Built a Java baseline (Holt-Winters level/trend/seasonal + seasonal-naive) behind a `Forecaster`
  interface, with cold-start handling (< 1 season → trend-only; no history → low-confidence floor).
- Chose **WAPE** over MAPE as the accuracy metric *because* MAPE explodes on near-zero periods —
  exactly where the long tail lives (ADR-0006).
- Backtested with time-series cross-validation and committed the report.

**Quantified result** (`docs/forecast-eval-report.md`, on the committed deterministic seed):

| Forecaster | Segments | n | **WAPE** | bias |
|---|---:|---:|---:|---:|
| SeasonalNaive | 24 | 2016 | 0.0755 | −0.0146 |
| **HoltWinters** | 24 | 2016 | **0.0686** | +0.0118 |

Holt-Winters cut pooled WAPE from ~7.6% to ~6.9% (a ~9% relative improvement) across 24 segments /
2,016 points — and the seasonal-naive it beat is *reused* as the in-process degradation fallback. One
body of arithmetic, two jobs: ship the better forecaster, keep the simpler one as the floor.

**Why it matters.** The number is the point — the forecast is justified by a backtest, not asserted,
and the metric choice is defended against the failure mode that actually bites (near-zero MAPE blow-up).

---

### Story 2 — Multi-tenant isolation + a read path that never fails closed

**Situation.** ~1M tenants share one service; a read must *never* leak across tenants and *never* go
down when the ML plane does (NFR1 availability + NFR8 isolation, the two top correctness bars).

**What I did.**
- Enforced tenant scope in a `TenantScopeFilter` ordered **first** — the path tenant must equal the
  authenticated tenant; a body-supplied id is never trusted. Isolation also holds in the cache key,
  the serving key, and the ML plane.
- Built a 4-state **degradation chain** (`fresh → stale → degraded → pending`) that terminates in
  pure-aggregation actuals — the always-available floor — so a total ML-plane outage still returns
  200 with an honest badge.

**Quantified / concrete result.**
- Cross-tenant read → **403** `tenant-mismatch`; unknown tenant → **404** `unknown-tenant`, both with
  RFC-7807 problem bodies — verified end-to-end by `TenantIsolationIT` and a Postman no-leakage folder
  (`t_demo` vs `t_acme`, asserting the other tenant's category is **absent**).
- Wiping the entire serving table mid-demo still returns **200 `degraded`** (never a 5xx), proven by
  `ForecastDegradationIT` and reproducible live (see `demo-script.md`).
- Coverage: 58/58 api unit tests green + 4 full-stack ITs (real Postgres + Redis) + a Newman/Postman
  CI gate; the degraded read is also an *observable* climbing counter (`topsales.read.total{status="degraded"}`).

**Why it matters.** Isolation and availability are the two failure modes that would be unforgivable in a
multi-tenant analytics platform — so they're the two I made *provable* (tests + a live, repeatable demo),
not just claimed.

---

## Transition into the deck

> "Those two stories — an earned forecast with a built-in floor, and a read path that's isolated and
> never fails closed — both fall out of one decision: keep ML off the read path. Let me show you the
> architecture that makes that true." → go to `deck/deck.md`, HLD section.
