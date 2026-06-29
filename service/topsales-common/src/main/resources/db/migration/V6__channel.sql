-- Phase 2.5 — channel as a first-class key dimension (ADR-0010).
-- Widen the aggregate key (tenant, category, day) -> (tenant, category, channel, day) so online
-- and offline demand are aggregated, ranked, and forecast independently; `all` is the read-time
-- sum of the channel-grain rows. docs/adr/0010-channel-as-first-class-dimension.md.

-- Backfill any existing Phase-2 rows to 'all' to satisfy NOT NULL, then drop the default so every
-- future write (ingestion + seed) must name a real channel — no silent 'all' rows that would
-- double-count under the read-time sum.
ALTER TABLE aggregates ADD COLUMN channel text NOT NULL DEFAULT 'all';

ALTER TABLE aggregates DROP CONSTRAINT aggregates_pkey;
ALTER TABLE aggregates ADD PRIMARY KEY (tenant_id, category_id, channel, bucket_date);

ALTER TABLE aggregates ALTER COLUMN channel DROP DEFAULT;

-- The actuals range scan filters tenant + date (+ optionally one channel); refresh the helper index.
DROP INDEX IF EXISTS ix_agg_tenant_cat_date;
CREATE INDEX ix_agg_tenant_chan_date ON aggregates (tenant_id, channel, bucket_date);

-- serving_rows / serving_active_version are deliberately untouched: they are empty until Phase 3,
-- where the '#channel' suffix on the serving pk is a writer string convention, not a schema change.
