package com.topsales.api.metrics;

import com.topsales.common.forecast.ServingTableRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Registers a single Micrometer gauge reporting <b>seconds since the newest serving-row {@code as_of}</b>
 * — i.e. how stale the freshest precomputed forecast is right now ({@link MetricNames#FORECAST_FRESHNESS_SECONDS}).
 * Paired with the read path's {@code status} counter this is the freshness SLO signal: an alert can fire
 * when this climbs past the SLO even before reads start reporting {@code stale}.
 *
 * <p>Deliberately <b>global</b> (no tenant tag): the value is {@code max(as_of)} across every active
 * serving pointer, which keeps the series cardinality at one and sidesteps dynamic per-tenant label
 * churn. It reuses the same {@link ServingTableRepository} the read path already depends on, reading
 * through the new {@link ServingTableRepository#newestAsOf()} seam.
 *
 * <p>The gauge holds a weak reference to {@code this} and pulls {@link #ageSeconds()} lazily at scrape
 * time, so the value is always current without a background thread.
 */
@Component
public class ForecastFreshnessGauge {

    /** Sentinel reported when no serving row exists yet (nothing forecast) — avoids a misleading 0. */
    static final double NO_DATA = Double.NaN;

    private final ServingTableRepository servingTable;

    public ForecastFreshnessGauge(ServingTableRepository servingTable, MeterRegistry meterRegistry) {
        this.servingTable = servingTable;
        Gauge.builder(MetricNames.FORECAST_FRESHNESS_SECONDS, this, ForecastFreshnessGauge::ageSeconds)
                .description("Seconds since the newest serving-row as_of (forecast staleness)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /**
     * Seconds elapsed since the newest serving-row {@code as_of}, or {@link #NO_DATA} (NaN) when the
     * serving table is empty. A future {@code as_of} (clock skew) clamps to 0 rather than reporting a
     * negative age.
     */
    double ageSeconds() {
        Optional<Instant> newest = servingTable.newestAsOf();
        if (newest.isEmpty()) {
            return NO_DATA;
        }
        long seconds = Duration.between(newest.get(), Instant.now()).getSeconds();
        return Math.max(seconds, 0L);
    }
}
