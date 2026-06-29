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
 * Holt's linear method (double exponential smoothing — level + trend, no seasonal). Used as the
 * cold-start branch for a series too short to learn a season ({@code 0 < span < m}): there isn't
 * enough history for a seasonal pattern, but a level and slope are still meaningful.
 * {@code ŷ(asOf+i) = level + i·trend}; each horizon's point is the sum over the next {@code h} days.
 */
public final class TrendOnlyForecaster implements Forecaster {

    private final double alpha;
    private final double beta;
    private final double z;
    private final double highMax;
    private final double mediumMax;

    public TrendOnlyForecaster(double alpha, double beta, double z, double highMax, double mediumMax) {
        this.alpha = alpha;
        this.beta = beta;
        this.z = z;
        this.highMax = highMax;
        this.mediumMax = mediumMax;
    }

    @Override
    public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
        SeriesPrep prep = SeriesPrep.of(history);
        double[] y = prep.values();
        int span = prep.span();
        int maxH = maxHorizon(ctx.horizons());
        double[] daily = new double[maxH];

        if (span == 0) {
            return WindowForecast.build(
                    daily, ctx.horizons(), 0.0, 1, ResidualIntervals.Growth.SQRT_H, z, highMax, mediumMax);
        }

        double level = y[0];
        double trend = span >= 2 ? y[1] - y[0] : 0.0;
        double[] resid = new double[Math.max(span - 1, 0)];
        int ri = 0;
        for (int t = 1; t < span; t++) {
            double oneStep = level + trend;
            resid[ri++] = y[t] - oneStep;
            double prevLevel = level;
            level = alpha * y[t] + (1 - alpha) * (level + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
        }

        for (int i = 1; i <= maxH; i++) {
            daily[i - 1] = level + i * trend;
        }

        double sigma1 = ResidualIntervals.rmse(resid);
        return WindowForecast.build(
                daily, ctx.horizons(), sigma1, 1, ResidualIntervals.Growth.SQRT_H, z, highMax, mediumMax);
    }

    private static int maxHorizon(int[] horizons) {
        int max = 0;
        for (int h : horizons) {
            max = Math.max(max, h);
        }
        return max;
    }
}
