# Stress Test Plan

Push the platform **past** its rated capacity to find the breaking point, confirm it degrades
**gracefully** (not catastrophically), preserves data integrity, and **recovers** to baseline once load
subsides. Distinct from load tests, which validate health *within* SLO.

> **Status:** spec for the perf env (P6+). Run in an **isolated** environment — these tests intentionally
> saturate resources and can trip alarms / OOM the app.

## Goals
1. Find the saturation point (knee of the latency curve) for reads and ingestion.
2. Verify **backpressure** beats collapse: the system sheds/queues load and returns honest errors rather
   than corrupting data, deadlocking, or crash-looping.
3. Verify **integrity invariants hold under chaos** (no double-counted events, no partial top-k served).
4. Verify **recovery**: after overload, latency/error-rate return to baseline without manual intervention.

## Method
- Ramp load until a threshold breaches (p99 cliff, error-rate spike, or resource ceiling), hold, then
  release and measure recovery time. Capture DB connection-pool saturation, Redis evictions, GC/heap,
  thread pools, and CPU.
- Pair each "break it" scenario with a **recovery assertion**.

## Scenarios
| ID | Scenario | What it stresses | Expected (graceful) behavior |
|---|---|---|---|
| ST-01 | Ramp reads to collapse | App threads, DB read pool | latency rises smoothly to a knee; beyond it, excess requests get fast **503/429** (backpressure), not unbounded queueing; **no 5xx-from-crash** |
| ST-02 | Ramp ingestion to collapse | Write pool, upsert contention | throughput plateaus; surplus shed/queued; **counts stay exact** (`received == applied+deduped+quarantined`) |
| ST-03 | **DB connection-pool exhaustion** | Hikari pool | requests beyond pool wait up to timeout then fail fast with a clean 503; pool never deadlocks; recovers when load drops |
| ST-04 | Giant batch (10k–100k events in one POST) | Memory, request limits | bounded: either accepted within memory budget **or** rejected with a clear 413/400 — **never** OOM-crash |
| ST-05 | Duplicate flood (same key ×100k, high concurrency) | UNIQUE constraint, dedupe race | exactly-once applied; the rest `deduped`; **zero** aggregate inflation; no deadlock on the unique index |
| ST-06 | Hot-key thundering herd (cache miss on one popular key, N concurrent) [P4] | Single-flight, recompute | one recompute; others wait/serve-stale; no recompute storm; no connection spike |
| ST-07 | Redis outage under load [P4] | Cache dependency | reads fall back to store (slower but **200**); when Redis returns, hit-rate recovers; no error cascade |
| ST-08 | Forecast-plane outage under load [P3/P4] | Degradation chain | reads serve last-good → seasonal-naive → actuals `pending`; **100% reads still 200**; dashboard renders flagged |
| ST-09 | Bedrock slow/erroring under load [P5] | Insight timeout/breaker | circuit breaker opens; template fallback; reads unaffected; no thread starvation from blocked AI calls |
| ST-10 | Memory pressure / sustained overload then release | Heap, GC, recovery | no leak; after release, p99 + error-rate return to baseline within a bounded recovery window |
| ST-11 | Single tenant monopolizes (one tenant 95% of load) | Fairness / isolation | other tenants still served within SLO (no total noisy-neighbor starvation); document any per-tenant limits |

## Corner cases
| ID | Case | Expected |
|---|---|---|
| ST-20 | Clock skew / future-dated `eventTime` (e.g. year 2200) | bucketed by its (skewed) tenant-local day; **not** an error; flagged in data-quality metrics if added |
| ST-21 | Very old back-dated events flooding (late arrivals) | additive upsert still correct (order-independent); aggregates eventually consistent |
| ST-22 | Pathological `categoryId` cardinality (1M categories, one tenant) | top-k read stays O(k); ranking memory bounded; no full-table sort blowup on the hot path |
| ST-23 | Mixed valid/malformed flood (50% garbage) | quarantine keeps up; valid throughput preserved; quarantine table growth bounded/monitored |
| ST-24 | Rapid migration/restart under live load | in-flight requests drain or fail-fast cleanly; on restart Flyway is idempotent; no duplicate apply |

## Integrity invariants (must hold in EVERY scenario)
- **Exactly-once aggregation:** for any event set, `sum_amount`/`order_count` equal the netted truth
  regardless of order, concurrency, retries, or duplicates.
- **No partial top-k:** a read never returns a half-swapped version (atomic `serving_active_version` flip).
- **Counts conserved:** `received == applied + deduped + quarantined` for every ingest response.
- **Tenant isolation never breaks under load:** no cross-tenant row ever appears, even during contention.

## Expected-error catalog (these are *correct* outcomes under stress)
| Condition | Expected response |
|---|---|
| Beyond capacity (backpressure) | **503 Service Unavailable** (or **429**) — fast, not a hang |
| Oversized request body | **413 Payload Too Large** (or 400) with a clear problem detail |
| Downstream (Redis/Bedrock/forecast) down | **200** with degraded/pending status — *never* a 5xx |
| Pool/timeout exhausted | fail-fast 503; recovers automatically when load eases |
</content>
