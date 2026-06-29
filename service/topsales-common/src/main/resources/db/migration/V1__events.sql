-- Durable event ledger + idempotency gate. The unique idempotency_key is the dedupe gate
-- that makes at-least-once delivery safe (exactly-once effect). See docs/lld.md §2, §6.
CREATE TABLE events (
  id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id       text          NOT NULL,
  order_id        text          NOT NULL,
  category_id     text          NOT NULL,
  amount          numeric(18,2) NOT NULL,          -- signed: RETURN/ADJUSTMENT are negative
  currency        text          NOT NULL,
  event_type      text          NOT NULL CHECK (event_type IN ('SALE','RETURN','ADJUSTMENT')),
  event_time      timestamptz   NOT NULL,
  bucket_date     date          NOT NULL,          -- tenant-local day of event_time
  idempotency_key text          NOT NULL,
  received_at     timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT uq_events_idem UNIQUE (idempotency_key) -- the dedupe gate
);
CREATE INDEX ix_events_tenant_bucket ON events (tenant_id, bucket_date);
