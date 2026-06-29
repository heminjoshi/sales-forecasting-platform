/**
 * Single source of truth for the Phase-6 metric contract that the Monitoring
 * stack (and its tests) reference when building CloudWatch dashboards/alarms.
 *
 * These are the *dotted* meter names exactly as emitted by Micrometer in the
 * serving API (see
 * `service/topsales-api/.../metrics/MetricNames.java`). The CloudWatch registry
 * publishes them dotted, under the `topsales-api` namespace.
 *
 * NOTE ON RENDERING: the local/`prometheus` registry renders the same meters
 * with dots → underscores and appends `_total` to counters
 * (e.g. `topsales.read.total` → `topsales_read_total`). Dashboards that target
 * Prometheus must use the underscore form; CloudWatch uses the dotted form
 * below. Keep both stacks pointing at THIS object so a rename can't drift.
 */
export const METRIC_NAMES = {
  /** Counter: read-path responses, tagged `status` (fresh|stale|degraded|pending) and `mode`. */
  READ_TOTAL: 'topsales.read.total',

  /** Counter: insight generations that fell back to the deterministic template. */
  INSIGHT_FALLBACK: 'topsales.insight.fallback.total',

  /** Gauge: seconds since the newest serving-row `as_of` (global) — forecast staleness. */
  FORECAST_FRESHNESS_SECONDS: 'topsales.forecast.freshness.seconds',

  /** Counter: serving-read faults swallowed by the fail-soft read path (a dropped degradation rung). */
  PROVIDER_FAULT: 'topsales.forecast.provider.faults.total',

  /** Actuator/Micrometer built-in: RED latency/throughput/errors for HTTP requests. */
  HTTP_SERVER_REQUESTS: 'http.server.requests',
} as const;

export type MetricName = (typeof METRIC_NAMES)[keyof typeof METRIC_NAMES];
