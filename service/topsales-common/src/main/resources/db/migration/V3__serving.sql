-- Precomputed top-k; versioned for atomic swap + rollback. Written by the forecast batch
-- (Phase 3); stays empty in Phase 2 (actuals-only). docs/lld.md §2, §8.
CREATE TABLE serving_rows (
  pk             text          NOT NULL,           -- 'tenant#window#mode'
  version        integer       NOT NULL,
  rank           integer       NOT NULL,           -- 1..k
  category_id    text          NOT NULL,
  value          numeric(18,2) NOT NULL,
  interval_low   numeric(18,2),                    -- null in actuals/degraded
  interval_high  numeric(18,2),
  confidence     text          CHECK (confidence IN ('HIGH','MEDIUM','LOW')),
  delta_vs_prior numeric(8,4),                     -- e.g. 0.1200 = +12%
  as_of          timestamptz   NOT NULL,
  PRIMARY KEY (pk, version, rank)
);

CREATE TABLE serving_active_version (
  pk             text PRIMARY KEY,                 -- 'tenant#window#mode'
  active_version integer     NOT NULL,
  as_of          timestamptz NOT NULL
);
