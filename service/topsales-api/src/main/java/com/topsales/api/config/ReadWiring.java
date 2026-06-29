package com.topsales.api.config;

import com.topsales.api.provider.PrecomputedForecastProvider;
import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.config.TopsalesProperties.Forecast;
import com.topsales.common.forecast.ForecastProvider;
import com.topsales.common.forecast.ServingTableRepository;
import com.topsales.forecast.model.SeasonalNaiveForecaster;
import com.topsales.ingestion.repo.JdbcServingTableRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the forecast read plane (Phase 4): the serving-table reader, the precompute provider over it,
 * and the in-process seasonal-naive forecaster used as the degradation fallback. Mirrors the batch's
 * {@code BatchConfig} — the {@code topsales-ingestion} serving repo is constructed explicitly rather
 * than component-scanned because it takes a plain {@code int versionKeep} (a scanned bean would fail
 * with "no qualifying bean of type 'int'"). The read side only ever calls {@code readActive}.
 *
 * <p>This is the {@code local} half of the profile seam (docs/lld.md §10); the designed {@code aws}
 * wiring swaps a DynamoDB-backed {@link ForecastProvider} behind the same interfaces.
 */
@Configuration
public class ReadWiring {

    /**
     * Serving-table port for reads. {@code versionKeep} only matters for the writer's prune step, but
     * the constructor requires it; we pass the same configured value so a stray read-side write would
     * behave identically to the batch.
     */
    @Bean
    public ServingTableRepository servingTableRepository(JdbcTemplate jdbc, TopsalesProperties props) {
        return new JdbcServingTableRepository(jdbc, props.forecast().versionKeep());
    }

    /** Built {@link ForecastProvider}: active-version serving rows; empty → degradation chain. */
    @Bean
    public ForecastProvider forecastProvider(ServingTableRepository servingTable) {
        return new PrecomputedForecastProvider(servingTable);
    }

    /**
     * The degradation fallback forecaster (chain tier 3, {@code degraded}): seasonal-naive computed
     * in-process from actuals when serving rows are unavailable. Reuses the exact baseline the batch
     * uses, parameterized from {@code topsales.forecast.*}, so on-the-fly forecasts match the
     * precomputed shape. It is the only {@code Forecaster} bean in the read app (the batch's
     * dispatcher lives behind the excluded {@code com.topsales.forecast} scan).
     */
    @Bean
    public SeasonalNaiveForecaster seasonalNaiveForecaster(TopsalesProperties props) {
        Forecast f = props.forecast();
        Forecast.Interval iv = f.interval();
        return new SeasonalNaiveForecaster(
                f.holtWinters().seasonLength(),
                iv.z(),
                iv.confidenceHighMax(),
                iv.confidenceMediumMax());
    }
}
