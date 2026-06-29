# ADR-0002: Java ↔ model coupling — data plane vs synchronous RPC

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-1; ADR-0001

## Context
The serving service is Java; the forecaster may eventually be a Python/ML model. How should they
couple — through data (the model writes a store the service reads) or through a synchronous call?

## Options

### A — Data-plane coupling (chosen)
The model writes the serving table; the service reads it.
- **Pros:** ML latency/availability never enter the read budget; clean separation of planes.
- **Cons:** forecasts are only as fresh as the last batch.

### B — Synchronous RPC (REST/gRPC) per request
The service calls the model live on the read path.
- **Pros:** freshest possible result.
- **Cons:** ML availability becomes read availability; inference latency + cost in the hot path.

## Decision
**A — data-plane coupling** via the versioned serving table.

## Why (requirements & assumptions)
The **A4** batch cadence makes per-request RPC pointless, and **availability (NFR1)** is the top
priority — A keeps a model outage off the read path. Pairs with ADR-0001 (precompute).

## If the assumption changes
If real-time scoring is required, add a **SageMaker real-time endpoint (REST)** behind the
`Forecaster` seam; reserve gRPC for self-hosted sub-10ms/streaming needs. The read contract is
unchanged — only the provider impl.

## Consequences
The only coupling between forecast and serving planes is the serving table. The JVM baseline fallback
(ADR-0005) preserves reads even when that table is unavailable.
