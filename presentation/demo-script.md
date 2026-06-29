# Live demo script

The cold-start demo sequence, the dashboard walk, and the **degradation beat** — with timings.
Acceptance (Build-Plan §160): *demo (incl. dashboard) runs clean from cold.* Mirrors the `[P9]`
rehearsal cases `MQ-90..96` in `test-plan/manual-qa-test.md`.

**Prereqs:** Docker Desktop, JDK 21+, Maven, a browser. **No Node, no AWS account, no internet**
(Chart.js is vendored). Two terminals (one holds the long-running `make run`).

**Total budget: ~6–7 min** inside the 60-min talk. Rehearse it cold ≥3× (Build-Plan §158).

---

## 0. Before the room (off-stage, ~2 min, do this beforehand)

Pull images and warm the build so nothing downloads on stage:

```bash
make up            # starts Postgres + Redis (docker compose)
make seed          # backfills ~18 months of seasonal, channel-split history for t_demo + t_acme
make run           # builds + boots the API on :8080 (LEAVE RUNNING in terminal 1)
make forecast      # writes versioned serving rows (terminal 2) — gives a FRESH forecast
```

Open `http://localhost:8080`, confirm a `fresh` badge renders. Then **disconnect wifi** to prove the
zero-dependency claim live. If you must demo fully cold, do steps 1–2 on stage (add ~90s).

---

## 1. Cold-start (on stage, ~60s) — "two commands and it's up"

> "Prerequisites are Docker, a JDK, and a browser. Watch — `make up` brings up Postgres and Redis,
> `make run` builds and boots the service; Flyway migrates the schema on boot."

```bash
make up
make run     # terminal 1, stays running
```

Point out: **no AWS, no Node, no internet.** Open `http://localhost:8080`.

---

## 2. The dashboard walk (~2 min) — the happy path

Tenant `t_demo`, mode **forecast**, window **month**, channel **all**, k **10**.

1. **Status badge** → `fresh`, with an "as of …" timestamp. *"It always tells you how fresh it is."*
2. **Ranked table** → top categories with value, **Δ vs prior**, **confidence**, and an **interval**.
3. **Insight line** → one grounded sentence — *"every number in it is one we computed; the LLM only
   verbalizes provided figures, validated."*
4. **Toggle mode actuals ↔ forecast** → actuals is the historical floor (always `fresh`).
5. **Change window** week / month / year, and **channel** all / online / offline → *"online and offline
   have genuinely different seasonality — channel is a first-class dimension, and `all` is their exact sum."*
6. **Change k** 10 → 5 → ranks stay contiguous.

> The chart renders from the **vendored** Chart.js — no CDN call (check the network tab if asked).

---

## 3. The degradation beat (~2 min) — the signature moment

> "Availability is the #1 requirement. Let me prove the read path survives a total ML-plane outage —
> I'll delete **every** forecast and bump the cache so nothing's hiding."

**Terminal 2 — wipe the serving table and invalidate the tenant's cache:**

```bash
docker compose -f local/docker-compose.yml exec -T postgres \
  psql -U topsales -d topsales -c 'TRUNCATE serving_rows, serving_active_version;'

docker compose -f local/docker-compose.yml exec -T redis \
  redis-cli INCR tenantver:t_demo
```

> **Why both steps:** the TRUNCATE removes the precomputed forecasts; the Redis `INCR` bumps the
> per-tenant cache version so a previously-cached `fresh` response can't be served. (There's no
> `make degrade` target by design — these two lines *are* the demo.)

**Refresh the forecast view.** What the room sees:
- The page **still renders** — **HTTP 200**, never a 5xx, never a blank.
- The badge flips to **`degraded`** (on-the-fly seasonal-naive from actuals) or **`pending`** (actuals floor).
- The **degradation banner** appears: *"⚠ Forecasts unavailable — showing an on-the-fly seasonal-naive
  estimate (low confidence)."*
- The table is still populated. *"This is the same arithmetic that doubles as our baseline forecaster —
  it runs in the service JVM, no Python, no ML plane."*

> This is exactly what `ForecastDegradationIT` asserts (`IT-FC-07`) — the demo and the test agree.

**Optional — show it's observable, not just visible:**
```bash
curl -s localhost:8080/actuator/prometheus | grep 'topsales_read_total'
# topsales_read_total{status="degraded",...} is present and climbing
```

---

## 4. Recovery (~30s) — "and it heals"

```bash
make forecast    # recompute + atomic version swap
```

Refresh → badge returns to **`fresh`** with intervals. *"The wipe was fully recoverable — a new batch
is an atomic swap, and rollback would be a flip back to the previous version."*

---

## 5. Close the demo → back to the deck

> "So: isolated per tenant, fast off precompute, and honest under failure — the read path never went
> down even when I deleted every forecast. That's the architecture paying off." → resume the deck
> (Scale & Perf / Q&A) or take questions.

---

## Failure-recovery cheatsheet (if something misbehaves on stage)

| Symptom | Fix |
|---|---|
| Dashboard empty after `make run` | You skipped `make seed` — run it (terminal 2), refresh |
| Forecast shows `pending` not `fresh` at start | `make forecast` hasn't run (or ran before `seed`) — run it after seed |
| Badge stays `fresh` after the wipe | You skipped the Redis `INCR` — bump `tenantver:t_demo`, refresh |
| Chart blank | Hard-refresh; vendored Chart.js is at `static/vendor/chart.umd.min.js` (no network needed) |
| Port 8080 busy | A prior `make run` is still up — stop it, or `make down && make up` |
| Containers won't start | `make down && make up`; check Docker Desktop is running |
