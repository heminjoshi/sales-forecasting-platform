-- Dead-letter for malformed events: captured, counted, never retried into the pipeline
-- (aws: SQS DLQ). docs/lld.md §3.1, §6.
CREATE TABLE quarantine (
  id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id   text,                                -- best-effort; may be null if unparseable
  payload     text        NOT NULL,                -- the raw rejected JSON
  reason      text        NOT NULL,                -- validation failure detail
  received_at timestamptz NOT NULL DEFAULT now()
);
