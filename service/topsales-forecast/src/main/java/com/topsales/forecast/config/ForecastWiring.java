package com.topsales.forecast.config;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.config.TopsalesProperties.Forecast;
import com.topsales.common.forecast.Forecaster;
import com.topsales.forecast.model.ColdStartForecaster;
import com.topsales.forecast.model.FlatPriorForecaster;
import com.topsales.forecast.model.HoltWintersForecaster;
import com.topsales.forecast.model.SeasonalNaiveForecaster;
import com.topsales.forecast.model.SparseRateForecaster;
import com.topsales.forecast.model.TrendOnlyForecaster;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the single {@link Forecaster} bean the batch consumes: a {@link ColdStartForecaster}
 * dispatcher composed from the baseline models, all parameterized from {@code topsales.forecast.*}
 * ({@link TopsalesProperties}).
 *
 * <p>The sub-models are deliberately plain POJOs constructed here — <b>not</b> {@code @Component}s —
 * so the dispatcher fully owns the routing wiring and there is exactly one {@code Forecaster} in the
 * context. Swapping the baseline for the designed SageMaker {@code Forecaster} (ADR-0004/0005) is a
 * change to this one method behind the unchanged seam.
 */
@Configuration
public class ForecastWiring {

    /** Activity floor below which a series is treated as intermittent (see {@link ColdStartForecaster}). */
    private static final double SPARSE_DENSITY_MAX = 0.4;

    @Bean
    public Forecaster forecaster(TopsalesProperties props) {
        Forecast f = props.forecast();
        Forecast.HoltWinters hw = f.holtWinters();
        Forecast.Interval iv = f.interval();
        int m = hw.seasonLength();
        double z = iv.z();
        double high = iv.confidenceHighMax();
        double med = iv.confidenceMediumMax();

        FlatPriorForecaster flatPrior = new FlatPriorForecaster();
        SparseRateForecaster sparseRate = new SparseRateForecaster(z, high, med);
        TrendOnlyForecaster trendOnly = new TrendOnlyForecaster(hw.alpha(), hw.beta(), z, high, med);
        SeasonalNaiveForecaster seasonalNaive = new SeasonalNaiveForecaster(m, z, high, med);
        HoltWintersForecaster holtWinters =
                new HoltWintersForecaster(hw.alpha(), hw.beta(), hw.gamma(), m, z, high, med);

        return new ColdStartForecaster(
                m, SPARSE_DENSITY_MAX, flatPrior, sparseRate, trendOnly, seasonalNaive, holtWinters);
    }
}
