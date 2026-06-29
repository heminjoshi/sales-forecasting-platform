# ADR-0001: Forecast compute strategy — precompute vs on-demand vs hybrid

- **Status:** Accepted
- **Related:** `hld.md` §5 (the top-level architectural fork), §14 (A→B→C code changes)

## Context
The top-level architectural fork is **where and when forecasts are computed**. This choice drives
read latency, availability, cost, and complexity more than any other. Three approaches are viable.

## Options

### A — Precompute / batch (chosen)
A scheduled job forecasts every series offline and writes precomputed top-k to a serving store; the
API serves precomputed rows.
- **Pros:** O(top-k) reads; highest availability (ML offline ≠ read outage); lowest cost (amortized);
  simplest hot path; ML never in the read budget.
- **Cons:** staleness up to one cadence; odd/arbitrary horizons may not be precomputed.

### B — On-demand / real-time
Forecast at request time (or via a streaming model endpoint) with a short cache.
- **Pros:** maximal freshness; arbitrary windows on demand.
- **Cons:** inference latency + cost in the hot path; **ML availability becomes read availability**;
  prohibitive at a ~1M-tenant fan-out.

### C — Hybrid / lambda
Precomputed batch base + a speed layer for recent data, merged at read.
- **Pros:** batch economics + near-real-time freshness; degrades to base if the speed layer fails.
- **Cons:** highest complexity — two code paths plus merge/reconciliation.

## Decision
**Approach A — precompute / batch.**

## Why (requirements & assumptions)
- **A4** (read-heavy, slow-evolving, daily freshness acceptable) → precompute's staleness is a
  non-issue and its availability/latency/cost advantages dominate.
- **A5** (~1M tenants) → B's per-request inference cost is prohibitive at this fan-out; precompute
  amortizes it.
- **NFR1** (availability) is the top priority; A keeps ML off the read path entirely.
- The **actuals read mode + degradation chain** already cover "last N units" and not-yet-precomputed
  cases without paying B's cost.

**Intentionally not built:** a real-time inference path or speed layer — unjustified under the
current freshness requirement (avoids over-engineering).

## If the assumption changes
- **Freshness → sub-hour:** migrate to **C** (add a speed layer; keep batch economics) — *not* B,
  unless precompute also becomes uneconomical (extreme query variety), in which case **B**.
- The migration is a **`ForecastProvider` swap behind a stable interface**, not a rewrite (§14), so
  committing to A now is cheaply reversible.

## Consequences
Reads are O(top-k) point lookups, Redis-fronted, stateless-autoscaled. The **batch refresh window**
becomes the first scaling limit (→ incremental refit + tiered cadence). Forecast and serving planes
couple only through the versioned serving table.
