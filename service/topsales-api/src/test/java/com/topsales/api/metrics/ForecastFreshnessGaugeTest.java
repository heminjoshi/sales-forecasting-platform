package com.topsales.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.topsales.common.forecast.ServingTableRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the forecast-freshness gauge (Phase-6 metrics); serving repo mocked. */
class ForecastFreshnessGaugeTest {

    @Test
    void reportsNaN_whenNoServingRowsExist() {
        ServingTableRepository repo = mock(ServingTableRepository.class);
        when(repo.newestAsOf()).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ForecastFreshnessGauge(repo, registry);

        double value =
                registry.get(MetricNames.FORECAST_FRESHNESS_SECONDS).gauge().value();
        assertThat(value).isNaN();
    }

    @Test
    void reportsPositiveAge_forAPastAsOf() {
        ServingTableRepository repo = mock(ServingTableRepository.class);
        when(repo.newestAsOf()).thenReturn(Optional.of(Instant.now().minusSeconds(120)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ForecastFreshnessGauge(repo, registry);

        double value =
                registry.get(MetricNames.FORECAST_FRESHNESS_SECONDS).gauge().value();
        // ~120s elapsed; allow slack for test execution time.
        assertThat(value).isGreaterThanOrEqualTo(120.0).isLessThan(200.0);
    }

    @Test
    void clampsFutureAsOfToZero_onClockSkew() {
        ServingTableRepository repo = mock(ServingTableRepository.class);
        when(repo.newestAsOf()).thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new ForecastFreshnessGauge(repo, registry);

        double value =
                registry.get(MetricNames.FORECAST_FRESHNESS_SECONDS).gauge().value();
        assertThat(value).isEqualTo(0.0);
    }
}
