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
| `tenants` | The tenant ids to generate for (e.g. `["t_demo","t_acme"]`). Each gets independent data (the RNG keys on tenant id); timezone + currency are read from `tenant_config`. |
| `historyDays` | Trailing window length ending **today** (so the dashboard always has fresh data). |
| `trendAnnual` | Multi-month upward trend (e.g. `0.15` = +15%/yr) the forecaster's trend term fits. |
| `returnRate` | Fraction of gross value netted out as signed returns (financial-domain correctness). |
| `categories[]` | `base` daily level, `aov` (avg order value → order counts), `onlineShare` (channel split), `sparse` (intermittent → cold-start). |
| `outlier` | A single one-off spike on a non-HVE day (distinct from recurring seasonality → outlier-dampening reasoning). |
| `seasonality` | Recurring-seasonality tunables read by `SeasonalityModel`: `weeklyOnline`/`weeklyOffline` (7 day-of-week factors, Mon..Sun), `monthly` (12 month factors, Jan..Dec), `noiseBand` (multiplicative noise width centered on 1.0 — `0.2` → `[0.9, 1.1)`), `sparseHitRate` (fraction of cells a `sparse` category fires on, e.g. `0.18`). |
| `hve` | High-volume-event multipliers read by `HveCalendar` (calendar anchors stay in code; only magnitudes are config): `blackFridayOffline`/`blackFridayOnline`, `cyberMondayOffline`/`cyberMondayOnline`, `primeDayOnline`/`primeDayOffline`, and the December ramp `decemberRampStart` (Dec 1) → `decemberRampEnd` (Dec 24) then `decemberPostDip` (from Dec 26). |

## Features baked into the data (each maps to a behavior to demonstrate)

- **Weekly + monthly seasonality** and a **multi-month trend**.
- **Channel-differentiated HVE calendar** (recurring, *not* outliers): Black Friday (offline-heavy),
  Cyber Monday (online-heavy), a December ramp + post-event dip, a mid-July Prime-Day-style event —
  see `HveCalendar`.
- A **sparse/intermittent** category (`cat_collectibles`) → cold-start / Croston reasoning.
- A **one-off outlier** (`outlier`) distinct from the recurring HVE → outlier-dampening reasoning.
- **Signed returns** (`returnRate`) → idempotent signed-event aggregation correctness.
