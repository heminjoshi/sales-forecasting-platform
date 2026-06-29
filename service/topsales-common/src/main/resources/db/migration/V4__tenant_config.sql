-- Supplies tz + currency per tenant (A6). Load before ingesting: bucketing is tenant-local.
-- docs/lld.md §2, §6.
CREATE TABLE tenant_config (
  tenant_id          text PRIMARY KEY,
  timezone           text NOT NULL,                -- IANA, e.g. 'America/Los_Angeles'
  reporting_currency text NOT NULL                 -- ISO 4217, e.g. 'USD'
);

-- A demo tenant so the local stack is usable immediately after `make up`.
INSERT INTO tenant_config (tenant_id, timezone, reporting_currency)
VALUES ('t_demo', 'America/Los_Angeles', 'USD');
