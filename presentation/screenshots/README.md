# screenshots/

Dashboard captures for the deck hero slide and the repo README. **Capture these during a rehearsal**
(they need a running stack + a browser — they're not produced in CI). Phase 10's README hero reuses them.

## How to capture

Bring the stack up and get a fresh forecast:

```bash
make up && make seed && make run && make forecast
```

Open `http://localhost:8080` (tenant `t_demo`, mode **forecast**, window **month**, channel **all**, k **10**).

1. **`dashboard-fresh.png`** — the happy path. Frame the whole card: the **`fresh`** status badge + "as of"
   timestamp, the ranked table (value / Δ vs prior / confidence / interval columns visible), the insight
   line, and the chart. This is the primary hero.

2. **`dashboard-degraded.png`** — the resilience moment. Run the wipe from
   `../demo-script.md` §3 (TRUNCATE `serving_rows, serving_active_version` + `redis-cli INCR tenantver:t_demo`),
   refresh the forecast view, and capture the **`degraded`** badge + the degradation banner with the table
   still populated. This is the "never fails closed" proof shot.

Keep them ~1600px wide, light background, no browser chrome / no personal bookmarks bar in frame.

## Status

> ⏳ **Not yet captured** — placeholder. Add the two PNGs above during the first cold rehearsal, then
> reference `dashboard-fresh.png` from `deck/deck.md` (Presentation-tier slide) and the repo `README.md`
> Architecture section.
