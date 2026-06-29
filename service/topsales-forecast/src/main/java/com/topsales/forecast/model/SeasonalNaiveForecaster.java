package com.topsales.forecast.model;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.forecast.math.ResidualIntervals;
import com.topsales.forecast.math.SeriesPrep;
import com.topsales.forecast.math.WindowForecast;

import java.util.List;

/**
 * Seasonal-naive baseline: the forecast for a future day is simply the observed value {@code m} days
 * earlier ({@code ŷ(asOf+i) = grid[asOf + i − m]}), which for {@code i > m} folds back to a cyclic
 * repeat of the last {@code m} observed days. Each horizon's point is the sum of those daily values.
 *
 * <p>Deliberately <b>self-contained</b> — it depends only on {@code topsales-common} plus this
 * module's {@code math/} helpers (no Spring, no other models) — because it is reused unchanged as the
 * Phase-4 degradation fallback when ML forecasts are unavailable.
 */
public final class SeasonalNaiveForecaster implements Forecaster {

    private final int seasonLength;
    private final double z;
    private final double highMax;
    private final double mediumMax;

    public SeasonalNaiveForecaster(int seasonLength, double z, double highMax, double mediumMax) {
        this.seasonLength = Math.max(seasonLength, 1);
        this.z = z;
        this.highMax = highMax;
        this.mediumMax = mediumMax;
    }

    @Override
    public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
        SeriesPrep prep = SeriesPrep.of(history);
        double[] v = prep.values();
        int span = prep.span();
        // Degrade the season to what history actually supports (matters only for very short series;
        // the dispatcher normally only routes here when span ≥ m).
        int m = Math.min(seasonLength, Math.max(span, 1));

        int maxH = maxHorizon(ctx.horizons());
        double[] daily = new double[maxH];
        if (span > 0) {
            for (int i = 1; i <= maxH; i++) {
                // Cyclic repeat of the last m observed days: index 0 of that window is grid[span-m].
                daily[i - 1] = v[span - m + ((i - 1) % m)];
            }
        }

        double sigma1 = ResidualIntervals.rmse(seasonalResiduals(v, span, m));
        return WindowForecast.build(
                daily, ctx.horizons(), sigma1, m, ResidualIntervals.Growth.NAIVE, z, highMax, mediumMax);
    }

    /** In-sample one-step (one-season) residuals {@code v[t] − v[t−m]}. */
    private static double[] seasonalResiduals(double[] v, int span, int m) {
        if (span <= m) {
            return new double[0];
        }
        double[] e = new double[span - m];
        for (int t = m; t < span; t++) {
            e[t - m] = v[t] - v[t - m];
        }
        return e;
    }

    private static int maxHorizon(int[] horizons) {
        int max = 0;
        for (int h : horizons) {
            max = Math.max(max, h);
        }
        return max;
    }
}
