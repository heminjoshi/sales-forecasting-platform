# Seed dataset

`seed-config.json` is the **committed, reproducible source of truth** for the synthetic demo data
(ADR-0010). The generator (`service/topsales-datagen`) reads it and regenerates **byte-identical**
data for a given run date — no large CSV/SQL dump is committed; the config + a fixed `globalSeed`
is the dataset.

## Run

```bash
make up        # local Postgres
make seed      # bulk-backfills pre-summed, seasonal, channel-split aggregates (trusted backfill)
make run       # (separate shell) boots the API + dashboard
make trickle   # posts live SaleEvents for "today" → the dashboard moves
```

## What the config controls

| Field | Meaning |
|---|---|
| `globalSeed` | Seeds the per-cell RNG (`hash(globalSeed, tenant, category, channel, epochDay)`), so generation is order-independent and reruns are identical. |
| `historyDays` | Trailing window length ending **today** (so the dashboard always has fresh data). |
| `trendAnnual` | Multi-month upward trend (e.g. `0.15` = +15%/yr) the forecaster's trend term fits. |
| `returnRate` | Fraction of gross value netted out as signed returns (financial-domain correctness). |
| `categories[]` | `base` daily level, `aov` (avg order value → order counts), `onlineShare` (channel split), `sparse` (intermittent → cold-start). |
| `outlier` | A single one-off spike on a non-HVE day (distinct from recurring seasonality → outlier-dampening reasoning). |

## Features baked into the data (each maps to a behavior to demonstrate)

- **Weekly + monthly seasonality** and a **multi-month trend**.
- **Channel-differentiated HVE calendar** (recurring, *not* outliers): Black Friday (offline-heavy),
  Cyber Monday (online-heavy), a December ramp + post-event dip, a mid-July Prime-Day-style event —
  see `HveCalendar`.
- A **sparse/intermittent** category (`cat_collectibles`) → cold-start / Croston reasoning.
- A **one-off outlier** (`outlier`) distinct from the recurring HVE → outlier-dampening reasoning.
- **Signed returns** (`returnRate`) → idempotent signed-event aggregation correctness.
