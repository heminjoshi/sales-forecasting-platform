package com.topsales.api.metrics;

/**
 * Canonical names for the custom Phase-6 Micrometer meters (scraped at {@code /actuator/prometheus}).
 * RED latency/throughput/errors come free from actuator's {@code http.server.requests}; these are the
 * domain-specific ML-quality and freshness signals layered on top.
 *
 * <p>Held as constants in one place so the emitting sites (read path, insight layer, freshness gauge)
 * and any dashboards/tests reference the exact same string — a typo'd meter name is otherwise a silent
 * gap that only shows up as a missing Prometheus series.
 */
public final class MetricNames {

    private MetricNames() {}

    /** Counter: read-path responses, tagged {@code status} (fresh|stale|degraded|pending) and {@code mode}. */
    public static final String READ_TOTAL = "topsales.read.total";

    /** Counter: insight generations that fell back to the deterministic template (emitted by the insight layer). */
    public static final String INSIGHT_FALLBACK = "topsales.insight.fallback.total";

    /** Gauge: seconds since the newest serving-row {@code as_of} (global) — forecast staleness. */
    public static final String FORECAST_FRESHNESS_SECONDS = "topsales.forecast.freshness.seconds";

    /** Counter: serving-read faults swallowed by the fail-soft read path (a dropped degradation rung). */
    public static final String PROVIDER_FAULT = "topsales.forecast.provider.faults.total";
}
