-- These 24 extra tenants make the platform a genuinely multi-tenant demo (26 total).
-- Each gets its own deterministic synthetic data via the generator's archetype layer (data/seed/seed-config.json).
INSERT INTO tenant_config (tenant_id, timezone, reporting_currency)
VALUES
  ('tenant_c', 'America/Chicago',      'USD'),
  ('tenant_d', 'America/Denver',       'USD'),
  ('tenant_e', 'Europe/London',        'USD'),
  ('tenant_f', 'Europe/Berlin',        'USD'),
  ('tenant_g', 'Asia/Tokyo',           'USD'),
  ('tenant_h', 'Australia/Sydney',     'USD'),
  ('tenant_i', 'America/Los_Angeles',  'USD'),
  ('tenant_j', 'America/New_York',     'USD'),
  ('tenant_k', 'America/Chicago',      'USD'),
  ('tenant_l', 'America/Denver',       'USD'),
  ('tenant_m', 'Europe/London',        'USD'),
  ('tenant_n', 'Europe/Berlin',        'USD'),
  ('tenant_o', 'Asia/Tokyo',           'USD'),
  ('tenant_p', 'Australia/Sydney',     'USD'),
  ('tenant_q', 'America/Los_Angeles',  'USD'),
  ('tenant_r', 'America/New_York',     'USD'),
  ('tenant_s', 'America/Chicago',      'USD'),
  ('tenant_t', 'America/Denver',       'USD'),
  ('tenant_u', 'Europe/London',        'USD'),
  ('tenant_v', 'Europe/Berlin',        'USD'),
  ('tenant_w', 'Asia/Tokyo',           'USD'),
  ('tenant_x', 'Australia/Sydney',     'USD'),
  ('tenant_y', 'America/Los_Angeles',  'USD'),
  ('tenant_z', 'America/New_York',     'USD');
