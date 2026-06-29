# Load Test Plan

Validate that the platform meets its latency/throughput SLOs under **expected** production-shaped load.
Load tests confirm the system is healthy at target volume; stress tests (separate plan) push past it.

> **Status:** the load harness is a P6+/perf-env deliverable. This plan is the spec; numbers below are
> **targets to validate**, calibrated to the design doc's read-heavy assumption (A4) and p99 goal (NFR3),
> not yet measured. Re-baseline once running on representative hardware.

## Tooling & method
- **Tool:** k6 (or Gatling/JMeter) driving the REST API; a separate producer for ingestion load.
- **Environment:** staging with production-class Postgres + Redis; app at the intended instance size.
  Load generator on a **separate** host (don't co-locate — it skews latency).
- **Warm-up:** 2 min ramp before measurement; discard warm-up samples. Seed realistic data first
  (`make seed` — months of seasonal, channel-split history) so reads hit populated aggregates/serving.
- **Each run reports:** throughput (req/s), latency p50/p90/p95/p99/max, error rate, and resource use
  (CPU, mem, DB connections, Redis hit-rate). A run is **green** only if all thresholds below hold.

## SLOs / pass thresholds
| Metric | Target | Hard fail |
|---|---|---|
| Read p99 (cache hit) | ≤ 50 ms | > 150 ms |
| Read p99 (cache miss → store) | ≤ 200 ms | > 500 ms |
| Ingest p99 (per request, batch ≤ 100) | ≤ 250 ms | > 750 ms |
| Error rate (5xx) | 0% | > 0.1% |
| Error rate (unexpected 4xx) | 0% | any |
| Redis cache hit-rate (steady state) | ≥ 90% | < 70% |
| Dedup correctness | 100% | any double-count |

## Workload model (expected mix)
Read-heavy per design: **~95% reads / ~5% writes**. Reads spread across tenants with a realistic
**hot-tenant skew** (Zipfian: top 10% of tenants ≈ 60% of traffic). Params vary across the legal space
(`mode`, `window`, `channel`, `k`) so the cache is exercised, not trivially saturated by one key.

## Scenarios
| ID | Scenario | Profile | Pass criteria |
|---|---|---|---|
| LD-01 | Steady-state read | Constant target QPS, 30 min, mixed params over many tenants | p99 within SLO; hit-rate ≥ 90%; 0 errors |
| LD-02 | Sustained ingestion | Producers POST at target events/s, batches of 50–100 | ingest p99 within SLO; `applied+deduped+quarantined == received` always; no lag growth |
| LD-03 | Mixed read+write | 95/5 concurrently for 30 min | both SLOs hold; reads reflect new writes after invalidation |
| LD-04 | Cache effectiveness | Re-request popular keys | ≥ 90% served from Redis; backing store QPS stays flat |
| LD-05 | Invalidation churn | Writes continuously bump hot tenants' versions | reads stay correct + within SLO; no unbounded recompute (single-flight holds) |
| LD-06 | Soak (endurance) | LD-03 mix for 4–8 h | no latency creep, no memory growth, no connection leak (flat DB pool) |

## Edge / corner cases (still within "expected", just unfavorable)
| ID | Case | Expected behavior |
|---|---|---|
| LD-10 | **Hot-tenant skew** — one tenant = 50% of reads | single-flight + cache absorb it; that tenant's p99 within SLO; others unaffected (no noisy-neighbor) |
| LD-11 | Large `k=50` reads at volume | within SLO (top-k read is O(k), one keyed partition read) |
| LD-12 | Max batch size (100) sustained | within ingest SLO; batch latency scales sub-linearly |
| LD-13 | High **dedupe ratio** (50% duplicates) | duplicates short-circuit cheaply; correctness 100%; no aggregate inflation |
| LD-14 | Cold cache at start of window | first reads miss → recompute; p99(miss) band held; converges to ≥90% hit-rate within warm-up |
| LD-15 | All `channel=all` vs per-channel mix [P2.5] | `all` (summed rollup) read cost comparable; per-channel keys cache independently |
| LD-16 | Empty/sparse tenants in the mix | empty-result reads are cheap 200s; don't pollute or thrash the cache |

## Expected errors / non-errors under load
- **No 5xx** at or below target load. Any 5xx is a failure to investigate.
- Degraded forecasts (forecast plane behind) surface as **200 + `stale`/`pending`/`degraded`**, *not* errors,
  and must still meet read-latency SLOs.
- 4xx should appear **only** for deliberately malformed synthetic requests; a rising legitimate-request
  4xx rate indicates a regression (e.g. validation tightened) and fails the run.
</content>
