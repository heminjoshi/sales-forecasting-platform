-- Authoritative rollup; PK = (tenant, category, day). Additive upsert keeps this independent
-- of arrival order/lateness; signed amounts let RETURN/ADJUSTMENT net correctly. docs/lld.md §2, §6.
CREATE TABLE aggregates (
  tenant_id    text          NOT NULL,
  category_id  text          NOT NULL,
  bucket_date  date          NOT NULL,
  sum_amount   numeric(18,2) NOT NULL DEFAULT 0,
  order_count  integer       NOT NULL DEFAULT 0,
  currency     text          NOT NULL,
  updated_at   timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, category_id, bucket_date)
);
CREATE INDEX ix_agg_tenant_cat_date ON aggregates (tenant_id, category_id, bucket_date);
