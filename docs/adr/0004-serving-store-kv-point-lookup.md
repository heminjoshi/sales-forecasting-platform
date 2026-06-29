# ADR-0004: Serving store shape — relational rows vs key-value point-lookup

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-3; ADR-0003

## Context
The read path needs the whole top-k for a `(tenant, window, mode)` in one cheap, cacheable operation
at p99 < ~150 ms (NFR3) under a read-heavy load (A4).

## Options

### A — KV point-lookup (chosen)
`pk = tenant#window#mode`, `sk = rank`; the whole top-k set is one partition read.
(The `pk` gains a `#channel` segment in Phase 2.5 — see [ADR-0010](0010-channel-as-first-class-dimension.md).)
- **Pros:** O(top-k) reads, trivially cacheable, scales flat; denormalized → no joins.
- **Cons:** write-on-refresh; denormalized data must be recomputed each batch.

### B — Query the aggregate/relational store at read time
- **Pros:** no extra store.
- **Cons:** ranking/aggregation on the hot path → slower; latency scales with data volume.

## Decision
**A — a precomputed KV serving table**, **versioned** for atomic swap + rollback. Locally a Postgres
table with a `serving_active_version` pointer (`lld.md` §2).

## Why (requirements & assumptions)
Read-heavy (**A4**) + the p99 target (**NFR3**) make a precomputed point-lookup the right shape;
versioning gives an atomic flip on refresh and an instant rollback target.

## If the assumption changes
If `k` or category cardinality explodes → add secondary indexing / pagination.

## Consequences
Writes are denormalized and batch-driven; all `k` rows of a version are written then the pointer is
flipped in one step. Old versions are retained for rollback and pruned by a lifecycle job.
