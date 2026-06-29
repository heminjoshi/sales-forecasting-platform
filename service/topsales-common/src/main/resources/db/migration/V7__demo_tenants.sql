-- A second demo tenant so the dashboard's tenant picker is genuinely multi-tenant and the
-- multi-tenant isolation story is demonstrable (each tenant's data is fully separate). Same
-- currency as t_demo (the demo dashboard formats USD) but a different timezone, so tenant-local
-- day bucketing visibly differs.
INSERT INTO tenant_config (tenant_id, timezone, reporting_currency)
VALUES ('t_acme', 'America/New_York', 'USD');
