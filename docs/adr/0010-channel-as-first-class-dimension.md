# ADR-0010: Channel — first-class dimension vs data-only enrichment

- **Status:** Accepted
- **Related:** `hld.md` §11, §12 DR-9; `lld.md` §3.2; `0004-serving-store-kv-point-lookup.md`
- **Built in:** Phase 2.5 (the Phase 2 walking skeleton ships the 3-tuple key `(tenant, category, day)` and gains `channel` via a follow-up migration)

## Context
Sales arrive over distinct **channels** (`ONLINE | OFFLINE`) whose demand shapes differ — offline
peaks on Black Friday, online on Cyber Monday; promotions and seasonality diverge. The question is
whether `channel` is part of the **key** the system aggregates, ranks, and forecasts on, or just an
attribute carried alongside and filtered at read time.

## Options

### A — Channel as a first-class dimension (chosen)
`channel` joins the aggregate key `(tenant, category, channel, day)` and the serving key
`tenant#window#mode#channel`. Forecasts are fit **at channel grain** and summed up to `all`; the API
exposes `channel=all|online|offline` (default `all`).
- **Pros:** online vs offline can be ranked and forecast **independently** — the channel-differentiated
  seasonality (a core forecasting-quality + demo point) is actually modeled; `all` stays **exact** as
  the sum of its parts.
- **Cons:** widens the key → more aggregate/serving rows and more forecast fits (category cardinality ×
  channels).

### B — Data-only enrichment (post-filter)
Keep the `(tenant, category, day)` key; carry `channel` only as a non-key attribute and filter at read.
- **Pros:** no key change; fewer rows and forecast fits.
- **Cons:** can't rank or forecast **per channel** — the differentiated seasonality collapses to a flat
  split applied after the fact; `all` and per-channel views can disagree.

## Decision
**A — channel in the key**, with the default API view `channel=all` (the summed rollup).

## Why (requirements & assumptions)
The differentiated per-channel seasonality only *exists* in the output if channel is modeled, not
filtered. With a small fixed channel set (2), the key-width cost is bounded and worth the independent
ranking/forecasting it buys. `all` remains exact because it is the sum of the channel-grain rows.

## If the assumption changes
If channel cardinality or fan-out becomes a problem (many low-volume channels) → **collapse low-volume
channels into `all` and treat channel as a post-filter** — i.e. degrade to Option B behind the *same*
API surface (`channel=all` keeps working unchanged).

## Consequences
A Flyway migration adds the `channel` column and extends both the aggregate PK and the serving `pk`.
The forecast batch fits per `(tenant, category, channel)` and writes an additional `all` rollup row set.
The dashboard gains one optional channel toggle; the read path's default (`all`) is unchanged for
existing callers.
