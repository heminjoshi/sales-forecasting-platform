# Demo Runbook

A copy-paste, cold-start walkthrough for demoing the platform end-to-end on a laptop, plus the
automated coverage gate (`make demo`) and a troubleshooting section. Pairs with
[`presentation/demo-script.md`](../presentation/demo-script.md) (the spoken narrative) — this doc is
the **commands**.

> **The one rule that prevents 90% of demo failures:** `make up` (database) must be running **before**
> `make run` (the app) or `make demo` (the API tests). A `connect ECONNREFUSED 127.0.0.1:8080` means
> the app isn't up; a Flyway/`Connection refused 5432` means Postgres isn't up.

---

## 0. Prerequisites

- **Docker Desktop** (Postgres + Redis run in containers)
- **JDK 21** (JDK 26 also builds fine)
- **Maven**
- A **browser**
- **Node** is only needed for `make demo` (Newman is fetched via `npx`) and the optional React SPA — the
  live dashboard itself needs no Node.

Verify: `docker --version && java -version && mvn --version`.

Dashboard URL once running: **http://localhost:8080**. The dev auth stand-in is the `X-Tenant-Id`
header; the dashboard sets it for you, and there are **26 tenants** (`tenant_a` … `tenant_z`).

---

## 1. The golden command sequence (full live demo)

Run these from the repo root. Order matters — see the dependency notes after.

```bash
# 1. Start Postgres + Redis (Flyway migrations apply on the app's first boot)
make up

# 2. Bulk-load 26 tenants of seasonal history (writes straight to the DB — fast)
make seed

# 3. Compute forecasts → versioned serving rows (so mode=forecast is "fresh")
make forecast

# 4. Boot the API + dashboard. THIS BLOCKS the terminal (Ctrl+C to stop).
make run
```

Then open **http://localhost:8080**.

**Dependency notes**
- `make seed` and `make forecast` write to the DB **directly** (no API needed), so run them before `make run`.
- `make run` is a **foreground server** — it holds the terminal. Anything you run *while it's up* goes in a **second terminal**.
- `make down -v` **wipes the database volume**. After any `down -v` you must redo `up → seed → forecast`.

---

## 2. The live demo (what to click and say)

### Beat 1 — Actuals, multi-tenant, distinct data
1. Dashboard open at `tenant_a`, mode **actuals**, window **month**, channel **all**, k **10**.
2. Point out the ranked table + chart + the **`fresh`** status badge + "as of" timestamp + the grounded
   insight line.
3. Switch the tenant dropdown to **`tenant_c`** → a *different* leader (grocery-led) and volume. Then
   **`tenant_f`** → a small "boutique" tenant. This shows genuine multi-tenant data, not a clone.
   *(Each tenant is shaped by a data "archetype" — see [§7](#7-where-the-data-comes-from).)*

### Beat 2 — Forecast with intervals
4. Set mode to **forecast** → the chart gains **confidence-interval whiskers**, the table gains
   **Δ-vs-prior / confidence / interval** columns, badge stays **`fresh`**.
   *(Screenshot: [`presentation/screenshots/dashboard-fresh.png`](../presentation/screenshots/dashboard-fresh.png).)*

### Beat 3 — "Never fails closed" (the degradation beat)
This is the headline resilience moment. In a **second terminal**, wipe the forecast serving plane and
clear the cache:

```bash
docker compose -f local/docker-compose.yml exec -T postgres \
  psql -U topsales -d topsales -c "TRUNCATE serving_rows, serving_active_version;"
docker compose -f local/docker-compose.yml exec -T redis redis-cli FLUSHALL
```

5. Back in the browser, **refresh the forecast view**. The badge flips to **`degraded`**, a banner
   appears ("Forecasts unavailable — on-the-fly seasonal-naive estimate, low confidence"), and **the
   table is still populated**. The read never 5xx'd — it fell back to a seasonal-naive estimate computed
   from actuals.
   *(Screenshot: [`dashboard-degraded.png`](../presentation/screenshots/dashboard-degraded.png).)*
6. To restore `fresh`: re-run **`make forecast`** in the second terminal, then refresh.

### Beat 4 (optional) — Watch it move live
With the app running, in a second terminal: `make trickle` posts live `SaleEvent`s through
`POST /api/v1/events` (exercising dedupe/idempotency). Refresh the dashboard to see actuals tick up.

---

## 3. The automated gate — `make demo`

`make demo` runs the Postman/Newman collection against the **running** API. It is a **degradation +
isolation + observability** gate, so it expects the forecast serving table to be **empty** (folder 6
asserts `status` is `degraded`/`pending` and that a `status="degraded"` sample appears on
`/actuator/prometheus`).

**Cleanest sequence (mirrors CI — seed, no forecast):**
```bash
make up
make seed
make run        # in terminal 1 (blocks)
# terminal 2:
make demo       # 17 requests, 54 assertions → all green
```

**If you already ran `make forecast`** (e.g. for the dashboard), the forecast plane is `fresh` and
folder 6 will fail. Put it back into the degraded state first:
```bash
docker compose -f local/docker-compose.yml exec -T postgres \
  psql -U topsales -d topsales -c "TRUNCATE serving_rows, serving_active_version;"
docker compose -f local/docker-compose.yml exec -T redis redis-cli FLUSHALL
make demo
```

What it covers: ingest (single/batch/dedupe) · actuals + forecast reads · the degradation fallback ·
prompt-injection grounding · Prometheus RED + ML-quality meters · CORS preflight · multi-tenant
isolation (cross-tenant 403, no leakage).

---

## 4. Other useful targets

| Command | What it does | Needs DB up? | Needs API up? |
|---|---|:--:|:--:|
| `make up` | Start Postgres + Redis | — | — |
| `make seed` | Bulk-load 26 tenants of history | ✅ | — |
| `make forecast` | Run the forecast batch → serving rows | ✅ | — |
| `make run` | Boot API + dashboard (**blocks**) | ✅ | — |
| `make trickle` | Post live events to the API | ✅ | ✅ |
| `make demo` | Newman API gate (degraded + isolation) | ✅ | ✅ |
| `make eval` | Regenerate the WAPE backtest report (pure-JVM) | — | — |
| `make test` | 150 unit tests | — | — |
| `make synth` | `cdk synth` the infra (no deploy) | — | — |
| `make down` | Stop containers (`-v` also wipes data) | — | — |

Health check anytime: `curl localhost:8080/actuator/health` → `{"status":"UP"}` with `db` + `redis` components.

---

## 5. Reset / teardown

```bash
# stop the app: Ctrl+C in the `make run` terminal
make down          # stop containers, keep data
make down && make up && make seed && make forecast   # clean reset with data
```

`make down` without `-v` keeps the Postgres volume (data survives). The `Makefile`'s `down` uses `-v`,
so it **wipes** — re-seed afterward.

---

## 6. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `connect ECONNREFUSED 127.0.0.1:8080` (e.g. `make demo`) | API not running | Start it: `make run` (after `make up`) |
| `Connection to localhost:5432 refused` / Flyway error on `make run` | Postgres not running | `make up` first; wait for healthy |
| `make demo` folder 6 fails (`status degraded or pending`) | Forecasts are `fresh` | Wipe serving rows + `FLUSHALL` (see [§3](#3-the-automated-gate--make-demo)) |
| Dashboard shows `pending` everywhere | Seeded but never forecast, **and** no actuals fallback wired | Run `make seed` then `make forecast` |
| Empty dashboard after a restart | `make down -v` wiped the DB | `make up → seed → forecast` again |
| Port 8080 already in use | A previous app instance is still up | `lsof -nP -iTCP:8080 -sTCP:LISTEN` then kill it |
| Containers not listed | Stack is down | `docker compose -f local/docker-compose.yml ps`; `make up` |

---

## 7. Where the data comes from

The dataset is **synthetic and deterministic** — a fixed seed (`globalSeed`) plus a committed spec
(`data/seed/seed-config.json`) regenerate byte-identical data every run, so nothing large is committed.
The core (`SeasonalityModel`) computes one value per `(tenant, category, channel, day)` cell as a
product of interpretable factors:

```
value = base × channelShare × trend × weekly × monthly × hve(date,channel) × archetype × noise
```

- **trend** — steady annual growth; **weekly/monthly** — per-channel seasonality curves
- **hve** — high-volume events (Black Friday, Cyber Monday, Prime-Day-style, December ramp) modeled as
  *recurring seasonality the forecaster learns*, not outliers
- **archetype** — a per-tenant shaper (scale + per-category weights) so each of the 26 tenants has a
  distinct category mix and volume (`tenant_a` is the balanced baseline)
- plus a one-off **outlier**, a **sparse** category (cold-start), and signed **returns**

Per-cell randomness is seeded by `hash(globalSeed, tenant, category, channel, day)`, so reruns match
exactly and the live `make trickle` is a seamless continuation of the seeded history. Two load modes
share the one core: `make seed` bulk-inserts pre-summed aggregate rows; `make trickle` emits individual
events through the ingestion API. See `service/topsales-datagen/` for the implementation.
