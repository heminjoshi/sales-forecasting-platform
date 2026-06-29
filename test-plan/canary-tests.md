# Canary Test Plan

Lightweight synthetic probes that run **after every deploy** (and continuously in staging/prod) to prove
a freshly rolled instance is healthy on real infrastructure, and to gate / auto-roll-back a progressive
rollout. Canaries are shallow, fast, idempotent, and side-effect-clean — they are **not** load or
correctness suites; they answer "is this build serving correctly right now?"

> **Status:** spec for the deploy pipeline (P6+/P7). Locally, the same checks form the `make demo` /
> smoke sequence. In `aws`, they run as a post-deploy job + a scheduled synthetic monitor.

## Principles
- **Synthetic tenant only:** all canary traffic uses a dedicated `t_canary` tenant (seeded in
  `tenant_config`). Never touch real tenant data.
- **Idempotent + self-cleaning:** canary events use deterministic idempotency keys
  (`canary:<deploy-id>:<n>`) so re-runs dedupe instead of inflating; a teardown step removes canary rows
  (or they target a disposable tenant/schema).
- **Fail closed on the *deploy*, open on the *service*:** a failing canary **blocks/rolls back the
  rollout** — but the running service must itself never fail closed (reads stay 200 + status).

## Probe sequence (ordered; abort + roll back on first hard failure)
| ID | Probe | Action | Pass criteria |
|---|---|---|---|
| CN-01 | Liveness | `GET /actuator/health` | 200, `status: UP` |
| CN-02 | Readiness / DB | health shows DB component | DB `UP`; Flyway at expected version |
| CN-03 | Migrations applied | check `flyway_schema_history` (or health detail) | latest expected version present, no failed migration |
| CN-04 | Ingest round-trip | POST one canary SALE (`t_canary`, fixed key) | **202**; `{1,1,0,0}` on first deploy-id, `{1,0,1,0}` on re-run (dedupe proves idempotency live) |
| CN-05 | Read-back | `GET /tenants/t_canary/top-categories?mode=actuals&k=5` | 200; the canary category appears with the expected value; `status=fresh` |
| CN-06 | Forecast mode floor | same read `mode=forecast` | 200; `status=pending` (P2) or `fresh` once P3+ live; **never** 5xx |
| CN-07 | Tenant isolation guard | `GET /tenants/t_other/...` with `X-Tenant-Id: t_canary` | **403** tenant-mismatch (security invariant holds in prod) |
| CN-08 | Unknown tenant | read a guaranteed-absent tenant (matching header) | **404** unknown-tenant |
| CN-09 | Error model intact | inspect a probe 4xx body | `application/problem+json` with `type/title/status/instance` |
| CN-10 | Dashboard served | `GET /` | 200; HTML shell loads; static assets resolve |
| CN-11 | Metrics endpoint | `GET /actuator/prometheus` [P6] | 200; key metrics (RED + ML-quality) present and named as alarms expect |
| CN-12 | Degradation badge [P4] | read a tenant with no fresh forecast | 200 with `degraded`/`pending` + `asOf`; UI badge renders |
| CN-13 | Teardown | delete canary events / quarantine for the deploy-id | clean; no canary residue left behind |
| CN-14 | CORS allow-list live [P7] | preflight `OPTIONS` + actual `GET` to the API with the **deployed UI** `Origin` (read-only, `t_canary`) | 200; `Access-Control-Allow-Origin` echoes the deployed origin; `Allow-Methods ⊇ GET` — the prod allow-list is wired |
| CN-15 | Cross-origin Vercel dashboard reachable [P7] | `GET` the deployed Vercel URL, then its API `GET` (`t_canary`, idempotent read) | Vercel URL **200** (SPA shell loads); the API `GET` returns **200** with `Access-Control-Allow-Origin` present — the cross-origin SPA→API hop works end-to-end |

## Progressive-rollout guardrails (auto-rollback triggers)
During a canary/blue-green or % rollout, compare the new fleet against baseline. **Roll back automatically** if any breach over the observation window:
| Guardrail | Threshold |
|---|---|
| 5xx error rate (new fleet) | > 0.5% (or > baseline + 0.2%) |
| Read p99 | > 1.5× baseline |
| Health flapping | any instance not steadily `UP` |
| Failed migration | any |
| Degraded-read ratio spike | unexplained jump vs baseline (forecast plane regressed) |
| Canary correctness (CN-05 value) | mismatch ⇒ immediate rollback |

## Edge / corner cases the canary must handle
| ID | Case | Expected |
|---|---|---|
| CN-E1 | Re-deploy same build / re-run canary | CN-04 dedupes (`deduped=1`); suite still green; no data drift |
| CN-E2 | Canary runs mid forecast-batch swap | read returns a consistent version (atomic swap) — no partial top-k flake |
| CN-E3 | Redis cold/unavailable on the new instance [P4] | CN-05 still 200 from store; CN-11 shows cache-degraded metric; **not** a rollback by itself unless guardrail breached |
| CN-E4 | Cold JVM (first request slow) | warm-up request excluded from p99 guardrail; CN probes use a small warm-up |
| CN-E5 | Partial deploy (1 of N instances bad) | guardrail catches the bad instance; rollout halts before full promotion |

## Expected errors (correct canary outcomes — do NOT trigger rollback)
- **403** on CN-07 and **404** on CN-08 are **expected passes** (they prove guarded behavior).
- A **200 + `degraded`/`pending`** during a forecast-plane lag is healthy serving, not a failure.
- Only **unexpected 5xx**, correctness mismatches, failed migrations, or guardrail breaches roll back.
</content>
