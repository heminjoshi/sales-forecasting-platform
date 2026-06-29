# Runbook — operations, alarms, degradation, recovery

Operational guide for the serving + batch planes. Architecture in [`hld.md`](hld.md); component
behaviour in [`component-deep-dive.md`](component-deep-dive.md); degradation spec in [`lld.md`](lld.md) §5.

> Built locally on Micrometer + actuator; in `aws` the same metric names back CloudWatch alarms → SNS.

## 1. SLOs

| SLO | Target | Source metric |
|---|---|---|
| Read availability | 99.9%+ (survives full ML-plane outage via degradation) | RED error rate on the read path |
| Read latency | p99 < ~150 ms | request timer |
| Forecast freshness | ≥ X% of tenants within cadence (default SLO 36h) | `forecast_freshness_ratio` |
| Ingestion lag | bounded; consumers keep up with stream | stream lag / queue depth |
| Durability | no loss of the raw event log | S3 / raw-log write success |

## 2. Alarms (metric → threshold → meaning → first action)

| Metric | Alarm when | Means | First action |
|---|---|---|---|
| `read_error_rate` | > 1% 5-min | reads failing (not just degraded) | check DB + cache health; §4.1 |
| `read_p99_ms` | > 150 ms sustained | latency regression | check Redis hit rate, hot partitions |
| `degraded_read_count` | spike | serving table missing/stale → JVM fallback active | check last batch success; §4.2 |
| `forecast_freshness_ratio` | < SLO | batch behind / failed | inspect Forecaster Job; §4.2 |
| `batch_success` | = 0 for a cadence | forecast batch failed | rerun batch (idempotent); §4.2 |
| `wape_rolling` | > segment threshold | model drift | trigger refit / champion-challenger; §4.4 |
| `stream_lag` / queue depth | rising | consumers behind | scale consumers; check poison events |
| `dlq_depth` | > 0 | malformed/poison events quarantined | inspect DLQ; §4.3 |
| `insight_fallback_rate` | high | Bedrock timing out/erroring | template floor active; check model/SSM config |

## 3. Degradation chain (expected, not an outage)

Forecast reads degrade in order — each step is honest via `status`, and reads keep succeeding:

`fresh` (serving rows within SLO) → `stale` (last-good beyond SLO) → `degraded` (JVM seasonal-naive
from actuals) → `pending` (actuals top-k, no forecast yet). Bedrock down anywhere → **template**
insight. The UI always renders something honest. A `degraded`/`pending` read is **not** a page —
it's the system working as designed; page only if reads actually 5xx (all of serving table, DB, and
actuals unavailable).

## 4. Procedures

### 4.1 Read errors (5xx)
1. Check `/actuator/health`; confirm Postgres + Redis reachable (`make up` locally).
2. Redis down → reads fall through to the DB (slower, correct); restore Redis, watch hit rate recover.
3. Postgres down → degrade to cache/replica; if actuals also unavailable, this is the only true 5xx —
   restore the DB.

### 4.2 Forecasts stale / batch failed
1. Confirm via `batch_success` / `forecast_freshness_ratio`.
2. Reads are already covered by the degradation chain (`stale`/`degraded`/`pending`) — no read outage.
3. Rerun the batch (`make forecast`); it is **idempotent** and writes a new version + flips the
   pointer atomically. If the new version looks wrong, **roll back** by pointing
   `serving_active_version` at the prior version.

### 4.3 Poison / malformed events (DLQ)
1. One bad event never stalls a partition — it's quarantined (local table / SQS DLQ) and counted.
2. Inspect the event + reason; fix the producer or transform; reprocess from the DLQ.

### 4.4 Model drift
1. `wape_rolling` over threshold for a segment → champion/challenger evaluates a candidate model.
2. Promote only on improvement; the `Forecaster` seam makes the swap a drop-in (ADR-0005).

## 5. Replay / disaster recovery

The **raw event log is the only precious data** (S3 / local filesystem dir). Everything downstream is
regenerable:

1. Stop the consumer (or pause ingestion).
2. Truncate `aggregates` (and clear `events` to reset the dedupe ledger).
3. Re-feed the raw log through ingestion → aggregates rebuild **identically** (additive upserts +
   idempotency key = exactly-once effect; order-independent).
4. Rerun the forecast batch to repopulate serving rows.

No code change is needed — recovery is a pure data operation.

## 6. Deploys

Blue/green for the stateless service; canary + champion/challenger for models; **versioned serving
table** so a bad forecast batch is a pointer-flip rollback, not a data migration.

## 7. Local build & tests

- `make test` — fast unit tests across the reactor (no Docker); runs everywhere.
- `make verify` — full build incl. Testcontainers `*IT` integration tests (real Postgres). This is
  what CI runs (`mvn verify`). The ITs need a reachable Docker daemon whose API version is
  compatible with the bundled docker-java client.
- **Known caveat (very new Docker Desktop):** docker-java (Testcontainers 1.21.x) defaults to Docker
  API `1.32`. Docker Engine ≥29 (Docker Desktop ≥4.77) raises the minimum API to `1.40` and returns
  HTTP 400, so the `*IT`s fail to start locally on such hosts while passing in CI (older Engine,
  min API `1.24`). Unit tests are unaffected. The end-to-end path is otherwise verifiable live:
  `make up` → `make run` → POST `/api/v1/events` → GET `/api/v1/tenants/{id}/top-categories`.
