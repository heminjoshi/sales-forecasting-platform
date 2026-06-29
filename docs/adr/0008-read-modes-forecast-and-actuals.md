# ADR-0008: Read modes — two modes vs forecast-only

- **Status:** Accepted
- **Related:** `hld.md` §12 DR-7; `lld.md` §5 (degradation chain)

## Context
Should the API expose both **forecast** (predicted) and **actuals** (historical) modes, or only
forecasts?

## Options

### A — Two modes (chosen)
Forecast + actuals behind one API and one response shape.
- **Pros:** actuals is pure aggregation → **always available**; the durable floor; covers
  "last N units"; underpins the degradation chain.
- **Cons:** two read paths to maintain.

### B — Forecast-only
- **Pros:** simpler API surface.
- **Cons:** no floor when forecasts aren't ready; cannot answer "last N actual."

## Decision
**Two modes** behind one contract (`TopKResponse`); `actuals` is always `fresh` from aggregates.

## Why (requirements & assumptions)
**Availability (NFR1)** — actuals has no ML dependency, so it's the durable floor and the terminal
step of the degradation chain (`pending`). The "selectable time frames" requirement implies both
historical and predicted views.

## If the assumption changes
None foreseen — actuals is cheap and strictly increases availability. If a tenant never needs history,
the mode simply goes unused.

## Consequences
The degradation chain (`lld.md` §5) terminates in actuals (`forecast_pending`), so a read always
succeeds with an honest status even during a total ML-plane outage.
