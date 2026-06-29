# Generator

There is **no standalone generator here** — this directory is a placeholder pointer. The synthetic
demo-data generator is implemented as a Spring module at **`service/topsales-datagen`**, driven by
the committed config in **`data/seed/seed-config.json`** (the reproducible source of truth — see
`data/seed/README.md`).

## Where the code lives

| Concern | Class |
|---|---|
| Recurring weekly/monthly seasonality + noise | `service/topsales-datagen/.../gen/SeasonalityModel.java` |
| Channel-differentiated high-volume-event calendar (Black Friday, Cyber Monday, Prime-Day, December ramp) | `service/topsales-datagen/.../gen/HveCalendar.java` |
| Bulk backfill straight into the rollup (trusted history) | `service/topsales-datagen/.../load/SeedLoader.java` |
| Live "today" `SaleEvent` posts so the dashboard moves | `service/topsales-datagen/.../load/TrickleRunner.java` |

## How to run

The generator is exercised through the top-level `make` targets, not from this directory:

```bash
make seed      # bulk-backfill months of seasonal, channel-split history (needs `make up`)
make trickle   # post live SaleEvents for "today" → the dashboard moves (needs `make up` + `make run`)
make eval      # backtest the forecasters on the committed seed → WAPE report (pure JVM)
```

Generation keys its per-cell RNG on a fixed `globalSeed`, so a given run date regenerates
byte-identical data — the config plus the seed *is* the dataset (no large CSV/SQL dump is committed).
See `data/seed/README.md` for what each config field controls.
