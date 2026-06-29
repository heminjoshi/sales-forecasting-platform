package com.topsales.forecast.model;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.forecast.math.ResidualIntervals;
import com.topsales.forecast.math.SeriesPrep;
import com.topsales.forecast.math.WindowForecast;

import java.util.List;

/**
 * Intermittent-demand baseline: predict the mean daily rate (total over the grid ÷ span, counting
 * gap-filled zeros) for every future day, so {@code pointValue(h) = rate · h}. Chosen by the
 * dispatcher when activity is too sparse ({@code density < ~0.4}) for a trend or seasonal model to
 * mean much.
 *
 * <p>Always reports {@code LOW} confidence — a flat mean rate over a bursty series is a deliberately
 * humble estimate. (Croston's method is the designed-only refinement behind the same seam; see the
 * package note.)
 */
public final class SparseRateForecaster implements Forecaster {

    private final double z;
    private final double highMax;
    private final double mediumMax;

    public SparseRateForecaster(double z, double highMax, double mediumMax) {
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

        double rate = 0.0;
        if (span > 0) {
            double sum = 0.0;
            for (double v : y) {
                sum += v;
            }
            rate = sum / span;
        }

        double[] daily = new double[maxH];
        for (int i = 0; i < maxH; i++) {
            daily[i] = rate;
        }

        double[] resid = new double[span];
        for (int t = 0; t < span; t++) {
            resid[t] = y[t] - rate;
        }
        double sigma1 = ResidualIntervals.rmse(resid);

        List<ForecastValue> values =
                WindowForecast.build(
                        daily, ctx.horizons(), sigma1, 1, ResidualIntervals.Growth.SQRT_H, z, highMax, mediumMax);
        return WindowForecast.cap(values, Confidence.LOW);
    }

    private static int maxHorizon(int[] horizons) {
        int max = 0;
        for (int h : horizons) {
            max = Math.max(max, h);
        }
        return max;
    }
}
