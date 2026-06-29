# ADR-0003: Aggregate store — Aurora Postgres vs DynamoDB

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-2; ADR-0004 (serving store)

## Context
Where do the per-`(tenant, category, day)` rollups live? They must support additive idempotent
upserts on the write path and range/group-by scans for actuals reads and forecaster history.

## Options

### A — Aurora Postgres (chosen for aggregates)
- **Pros:** natural relational range/group-by; transactions; indexing; integrity.
- **Cons:** needs partitioning + read replicas at extreme scale.

### B — DynamoDB
- **Pros:** predictable horizontal scaling at extreme write rates.
- **Cons:** awkward for range/group-by analytics; aggregation patterns fight the model.

## Decision
**Aurora Postgres for aggregates**, paired with a **DynamoDB-shaped serving table** for the
point-lookup read path (ADR-0004) — best of both. Locally both are Postgres tables.

## Why (requirements & assumptions)
Aggregation access is relational (range/group-by, transactional additive upsert), so it belongs in
Postgres; serving is a point lookup, which belongs in a KV store. Splitting by access pattern beats
forcing one engine to do both.

## If the assumption changes
- If aggregate access becomes pure KV at extreme write rates → move aggregates to **DynamoDB**.
- If heavy analytical scans dominate → add a **columnar/warehouse** engine for analytics.

## Consequences
Two stores to operate, but each is simple and matched to its workload. Partition aggregates by tenant
+ replicas at 10× (`hld.md` §15).
