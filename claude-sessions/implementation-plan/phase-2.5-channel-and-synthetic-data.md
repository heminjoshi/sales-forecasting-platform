# Phase 2.5 — Synthetic data + channel dimension

> Source: `private/Build-Delivery-Plan-v3.md` §2.5 · Workstreams **[WS-E, WS-B, WS-C]** · Effort **M**.
> Implements **ADR-0010** (`docs/adr/0010-channel-as-first-class-dimension.md`) and feeds Phase 3.
> Must land **before** Phase 3 — the forecaster needs structured, seasonal, channel-differentiated
> data; random data makes WAPE meaningless and the chart flat.

## Objective & acceptance
Make **channel** a first-class key dimension and build a **deterministic synthetic-data generator**
that backfills months of seasonal, channel-split history and can trickle live events that continue it.

**Acceptance:** `make seed` bulk-loads months of channel-differentiated, seasonal history; `make
trickle` posts live events that continue it and the dashboard updates; the data visibly shows
weekly/monthly seasonality, a trend, a sparse category, an outlier, channel-split BF/CM spikes, and
returns. (ADR-0010 is already written.)

## Current state at start
Phase 2 walking skeleton merged: actuals-only vertical slice. The aggregate key is the **3-tuple**
`(tenant_id, category_id, bucket_date)` (`V2__aggregates.sql`); `SaleEvent`/`AggregateRow` have **no
channel**. `serving_rows` exists but is **empty** (no forecast writer yet). `make seed`/`make demo`
are Makefile stubs; `data/seed/` is a `.gitkeep`. Modules `topsales-forecast`/`-insight` are empty
stubs. `*IT` (Testcontainers) are CI-only on this dev host.

## Decisions locked this phase

| # | Decision | Value / why |
|---|---|---|
| 1 | Generator home | New leaf module `service/topsales-datagen` — a small Spring Boot app (`main` + `CommandLineRunner` dispatching on a `seed`/`trickle` arg) depending on `topsales-common` + `topsales-ingestion`. **Nothing depends on it**, so it never enters the bootable `topsales-api` graph. *(Why a module, not a package in ingestion: a dev-only DB-writing `main` would risk being component-scanned into the api app; a leaf module keeps it out.)* |
| 2 | `seed` write path | New `AggregateRepository.bulkUpsert(List<AggregateRow>)` → `JdbcTemplate.batchUpdate`, conflict target = the full new key, `DO UPDATE SET sum_amount=EXCLUDED.…, order_count=EXCLUDED.…` (**overwrite → re-runnable** to an identical state). Clears the seeded range first. Trusted backfill — deliberately bypasses the ingestion/idempotency path. *(Not `upsertAdditive` per row: that increments `order_count` by 1 — wrong for pre-summed rows, and far too many calls.)* |
| 3 | `trickle` path | Emits real `SaleEvent`s → `RestClient` POST `/api/v1/events` with `X-Tenant-Id` → the consumer's dedupe/upsert. Powers the "watch the dashboard move" demo and exercises idempotency. |
| 4 | Channel modeling (two enums) | Domain `Channel{ONLINE,OFFLINE}` — **uppercase** wire (like `EventType`), on `SaleEvent`/`AggregateRow`/`AggregateDelta`; a persisted event always has a concrete channel. Read-side `ChannelFilter{ALL,ONLINE,OFFLINE}` — **lowercase** `@JsonValue`/case-insensitive `@JsonCreator`, default `ALL` (like `Window`/`Mode`). `all` for **actuals** is **summed at read time** (no stored `all` rows) — *contrast Phase 3 forecasts, which materialize an `all` serving-row set because the serving table is a point-lookup KV with no read-time aggregation.* |
| 5 | V6 migration | `V6__channel.sql` widens the **aggregates** PK to `(tenant_id, category_id, channel, bucket_date)`: `ADD COLUMN channel text NOT NULL DEFAULT 'all'` (backfills any Phase-2 rows) → drop+re-add PK → `ALTER … DROP DEFAULT` (forces every future write to name a real channel → no silent `all` rows that would double-count under the read-time sum) → swap the helper index to `(tenant_id, channel, bucket_date)`. **No CHECK** (app-layer enum is the guard). **Do not touch `serving_rows`** — it's empty; the `#channel` suffix is a Phase-3 *writer string convention*, not a schema change. |
| 6 | Seed dataset | Commit a deterministic `data/seed/seed-config` (fixed tenants, categories, channel shares, HVE calendar params, `GLOBAL_SEED`, date range) + a `README.md`; the generator regenerates **byte-identical** data. *(Not a frozen CSV/SQL dump: it bloats the repo and drifts from the generator.)* Optional: one tiny golden CSV as a unit-test fixture. |

## Steps (as planned)
Schema/contract first (the shared base, like Phase 2), then ingestion → read → UI → generator → wiring.

1. **V6 migration.** Add `service/topsales-common/src/main/resources/db/migration/V6__channel.sql`
   per decision #5. *Why first:* every module's Testcontainers IT and the bootable app resolve one
   schema from `topsales-common`'s classpath; the widened key underpins everything below.
   *Verify:* `make up` then a fresh app boot migrates 6 versions; `\d aggregates` shows the new PK.
2. **Domain + enums (`topsales-common`).** New `domain/Channel.java`, read-side
   `ChannelFilter` (place beside `Window`/`Mode`). Add `channel` to `domain/SaleEvent.java`,
   `domain/AggregateRow.java`, `domain/AggregateDelta.java`, `api/TopKQuery.java`. Extend
   `repository/AggregateRepository.java`: add `bulkUpsert(List<AggregateRow>)` and make
   `rangeByCategory(tenantId, from, to, ChannelFilter)` channel-aware.
   *Verify:* `mvn -pl topsales-common test` green; enum round-trip tests pass.
3. **Ingestion plumbing (`topsales-ingestion`).** `repo/JdbcAggregateRepository.java`:
   `UPSERT_ADDITIVE`, new `bulkUpsert`, and `RANGE_BY_CATEGORY` carry `channel`; `ROW_MAPPER`
   reads it (range adds `AND channel = ?` unless `ALL`). `service/IngestionService.java`: add
   "missing channel" to validation; thread `channel` into the `AggregateDelta`. (Controller body
   now requires `channel`; no controller code change — Jackson parses the whole `SaleEvent`.)
   *Verify:* `IngestionServiceTest` — channel flows into the delta; missing channel → quarantined.
4. **Read path + API (`topsales-api`).** `web/TopCategoriesController.java`: add
   `@RequestParam(defaultValue="all") String channel`, parse via `ChannelFilter.from(...)` (bad
   value → 400, consistent with `Mode`/`Window`), pass into `TopKQuery`. `service/ActualsService.java`:
   pass the `ChannelFilter` into `rangeByCategory`; the existing merge-by-category yields the `all`
   sum (both channels) or the filtered single channel — no new ranking code.
   *Verify:* `ActualsServiceTest` — `ALL` sums both, `ONLINE` filters, default `ALL`.
5. **Dashboard.** `static/index.html`: one `<select id="channel">` (`all|online|offline`, default
   `all`). `static/app.js`: read it, append `&channel=` to the fetch URL. No new columns/chart change.
   *Verify:* dashboard at `/` shows the toggle; switching channel re-queries and re-renders.
6. **Generator module (`service/topsales-datagen`).** New `pom.xml` (Spring Boot app; deps
   `topsales-common`, `topsales-ingestion`, starter-jdbc, web `RestClient`); register in
   `service/pom.xml` `<modules>`.
   - `SeasonalityModel` (pure, unit-testable): per `(tenant, category, channel, day)` cell,
     `value = base(cat) × channelShare(cat,channel) × trend(day) × weekly(dow) × monthly(month)
     × hve(date,channel) × noise`, where `noise` is a `SplittableRandom` seeded by a mixing hash
     of `(GLOBAL_SEED, tenant, cat, channel, epochDay)` → **order-independent, byte-reproducible**;
     so seed and trickle agree and reruns are identical. `orderCount = max(1, round(value/AOV))`.
   - `HveCalendar.hve(LocalDate, Channel)` (1.0 outside windows): BF = 4th Friday of Nov, CM = the
     following Monday, December ramp ×1.5→×2.0 with a small post-event dip (Dec 26–31), a fixed
     mid-July Prime-Day-style event. **Per-channel multipliers:** offline BF ×5 / CM ×1.5; online
     CM ×6 / BF ×3; both Dec ×1.5–2.
   - Data features: a **sparse/intermittent** category (near-zero base, fires only when `rng<p≈0.15`);
     a deliberate **one-off outlier** (hard-coded `(cat, channel, date)` ×10 on a non-HVE day);
     **signed returns** (a fraction of value returns as negative — folded into net `sum_amount` in
     the bulk path, emitted as `RETURN` events in the trickle path).
   - `GeneratorConfig` (loads `data/seed/seed-config`), `SeedLoader` (Flyway migrate → clear →
     `bulkUpsert`), `TrickleRunner` (POST events), `CommandLineRunner` dispatch on the mode arg.
   *Verify:* `SeasonalityModelTest` — determinism, HVE per-channel asymmetry, sparse zeros, returns.
7. **Makefile + seed dataset + Postman.** `Makefile`: replace the `seed` stub, add `trickle`
   (two-step build like `run:` — `mvn -pl topsales-datagen -am … install` then run with the mode
   arg). `make seed` = bulk backfill (assumes `make up`); `make trickle` = live POST (assumes
   `make run`). Commit `data/seed/` (config + README). Add `channel` to Postman sample bodies + a
   `channel=online` read; update README curl examples.
   *Verify:* on a cold clone — `make up && make seed` populates aggregates; open dashboard → seasonal,
   channel-split top-k; `make run` in another shell + `make trickle` → the dashboard moves.
8. **Tests.** Enum wire round-trips (`Channel` uppercase, `ChannelFilter` lowercase + default +
   bad-value). `JdbcAggregateRepositoryIT`: two channels for the same cat/day stay **separate rows**;
   `bulkUpsert` re-runnable (overwrite); `rangeByCategory` with `ONLINE` filters, with `ALL` returns
   both. `ActualsServiceTest`/`TopCategoriesControllerTest`: channel param + default + 400.
   `SeasonalityModelTest` as above. `*IT` stay CI-only per the documented Docker-host caveat;
   `make test` (unit) is the everywhere-green gate.

## Acceptance checklist  — Phase 2.5 done when all true  ✅ (verified live)
- [x] `make seed` bulk-loads months of channel-differentiated, seasonal history (re-runnable).
- [x] `make trickle` posts live events that continue the seeded history; the dashboard updates.
- [x] Data visibly shows weekly/monthly seasonality, a trend, a sparse category, a one-off outlier,
      channel-split BF/CM spikes, and signed returns.
- [x] API `channel=all|online|offline` (default `all`); `all` = exact read-time sum of channels.
- [x] Dashboard has the channel toggle and re-renders on change.
- [x] V6 migrates cleanly; aggregates PK includes `channel`; ingestion requires a real channel.
- [x] `make test` green; `*IT` written (CI).

## Out of scope / deferred
- `serving_rows`/`serving_active_version` `#channel` key — a **Phase-3 writer convention**; no rows
  exist yet, so no migration now.
- Forecast `all` **materialization** (sum of per-channel fits into an `all` serving-row set) — Phase 3.
- 📐 Channel-collapse / post-filter fallback (ADR-0010 "if the assumption changes") — designed, not built.
- 📐 Appending seeded history's raw events to the NDJSON log (rebuild-from-raw story) — optional.
- Redis cache keyed by channel — Phase 4.

## Addendum — multi-tenant demo polish (built after the original plan)
Added to make the demo flow smoother and to exercise the multi-tenant isolation story:
- **Tenant picker.** New `GET /api/v1/tenants` (`TenantsController` + `TenantConfigRepository.allTenantIds()`)
  returns the configured tenant ids; the dashboard's tenant field is now a **dropdown** populated
  from it (was a free-text input), and re-loads on change. Documented as a demo/dev affordance —
  production tenant discovery is an admin/auth concern, not a public listing.
- **Second seeded tenant.** `V7__demo_tenants.sql` adds `t_acme` (America/New_York, USD). The
  generator is now **multi-tenant**: `SeedConfig.tenants` is a list; `SeasonalityModel` takes
  `(tenantId, currency)` per call (tz + currency read from `tenant_config` via `TenantProfile`);
  `make seed`/`make trickle` loop tenants. The per-cell RNG already keys on tenant id, so each
  tenant gets independent data. *(`allTenantIds()` is also the method the Phase-3 batch needs.)*
- **Contract reconciliation.** The synced OpenAPI already specified `channel` on `TopKResponse` and
  a required `SaleEvent.channel`; the code now matches — `TopKResponse` **echoes** the requested
  `channel` (read-side `all|online|offline`), and `openapi.yaml` gained the `/api/v1/tenants` path +
  `TenantsResponse` schema and marks `SaleEvent.channel` required.
- *Verified live:* `GET /api/v1/tenants` → `["t_acme","t_demo"]`; both tenants carry independent
  seeded data; cross-tenant read (`t_acme` while authed `t_demo`) → 403; the response echoes `channel`.

## Addendum — dashboard scope labels + window range (built after the original plan)
- **Window range in the response.** `TopKResponse` gains `windowFrom`/`windowTo` (`LocalDate`, ISO),
  populated by `ActualsService` (which already computed them in the tenant's tz) and threaded through
  the `mode=forecast` relabel. `openapi.yaml` `TopKResponse` schema + example updated. *(Forecast
  reuses the actuals window for now; the field semantics — "range this data covers" — carry into the
  Phase-4 forward-looking forecast window.)*
- **Dashboard scope label.** The `#meta` row now shows a scope line built from the response itself —
  e.g. `Actuals · Month · All — May 31 – Jun 29, 2026` (mode/window/channel + the date range) — so the
  rendered table/chart are self-describing for both actuals and forecast. Dates are formatted from the
  ISO strings without `Date()` to avoid browser-timezone day-shifts.
- *Verified live:* `…?mode=actuals&window=month` → `windowFrom=2026-05-31, windowTo=2026-06-29`;
  `mode=forecast&window=week` → `status=pending` with `2026-06-23 … 2026-06-29`.

## Known enhancement (documented, not built)
- **Cap `k` to the tenant's category count.** Today `k > N` is already graceful — the read path
  returns all `N` categories (no error). A clean future change: return a `categoryCount` (distinct
  categories in the window) on the response and cap the dashboard's `k` input `max` to it. Low value;
  deferred. (Noted in the `k` param description in `openapi.yaml`.)
