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
 * Additive Holt-Winters triple exponential smoothing (level + trend + seasonal). The daily forecast
 * is {@code ŷ(asOf+i) = level + i·trend + seasonal[phase]} and each horizon's point is the sum over
 * the next {@code h} days.
 *
 * <p><b>Additive, not multiplicative</b>, on purpose: this data carries zeros (no-sale days) and
 * negatives (returns), which would make a multiplicative seasonal term divide by zero or flip sign.
 * The additive seasonal is just a money offset, so it is safe everywhere; the trend term absorbs the
 * multi-month slope. All smoothing runs in {@code double}; rounding to money happens once, at the
 * interval boundary in {@link ResidualIntervals}.
 *
 * <p>Initialization makes a perfectly additive constant-trend series a fixed point of the recurrence
 * (level seeded at the <em>end</em> of the first season via the season-mean slope), so such a series
 * is reproduced exactly — which is what the unit tests pin.
 */
public final class HoltWintersForecaster implements Forecaster {

    private final double alpha;
    private final double beta;
    private final double gamma;
    private final int seasonLength;
    private final double z;
    private final double highMax;
    private final double mediumMax;

    public HoltWintersForecaster(
            double alpha,
            double beta,
            double gamma,
            int seasonLength,
            double z,
            double highMax,
            double mediumMax) {
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
        this.seasonLength = Math.max(seasonLength, 1);
        this.z = z;
        this.highMax = highMax;
        this.mediumMax = mediumMax;
    }

    @Override
    public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
        SeriesPrep prep = SeriesPrep.of(history);
        double[] y = prep.values();
        int span = prep.span();
        int m = seasonLength;
        int maxH = maxHorizon(ctx.horizons());
        double[] daily = new double[maxH];

        if (span < m) {
            // Not enough for one season; degrade to a flat last-value level (no seasonal/trend).
            double level = span == 0 ? 0.0 : y[span - 1];
            for (int i = 0; i < maxH; i++) {
                daily[i] = level;
            }
            return WindowForecast.build(
                    daily, ctx.horizons(), 0.0, m, ResidualIntervals.Growth.SQRT_H, z, highMax, mediumMax);
        }

        // --- Initialization (additive) ---
        int seasons = span / m;
        double[] seasonMean = new double[seasons];
        for (int s = 0; s < seasons; s++) {
            double sum = 0.0;
            for (int j = 0; j < m; j++) {
                sum += y[s * m + j];
            }
            seasonMean[s] = sum / m;
        }
        double trend = seasons >= 2 ? (seasonMean[1] - seasonMean[0]) / m : 0.0;
        // Seed the level at the END of season 0 (index m-1) by extrapolating the season-0 mean (which
        // sits at the season's centre) forward along the trend — this is the fixed-point seed.
        double level = seasonMean[0] + trend * (m - 1) / 2.0;

        // Detrend each observation against the seeded trend line (base at index t = the level
        // extrapolated to t), then average by phase. Subtracting the season mean instead would leave
        // the intra-season slope in the seasonal term and break the additive fixed point.
        double[] seasonal = new double[m];
        for (int t = 0; t < seasons * m; t++) {
            int phase = t % m;
            double base = level + trend * (t - (m - 1));
            seasonal[phase] += y[t] - base;
        }
        for (int phase = 0; phase < m; phase++) {
            seasonal[phase] /= seasons;
        }
        // De-mean the seasonal components so they sum to ~0 (the level absorbs the average).
        double sMean = 0.0;
        for (double sv : seasonal) {
            sMean += sv;
        }
        sMean /= m;
        for (int phase = 0; phase < m; phase++) {
            seasonal[phase] -= sMean;
        }

        // --- Smoothing pass over the first full season onward, collecting one-step residuals ---
        double[] resid = new double[span - m];
        int ri = 0;
        for (int t = m; t < span; t++) {
            int phase = t % m;
            double prevLevel = level;
            double prevTrend = trend;
            double sPrev = seasonal[phase];
            double oneStep = prevLevel + prevTrend + sPrev;
            resid[ri++] = y[t] - oneStep;
            level = alpha * (y[t] - sPrev) + (1 - alpha) * (prevLevel + prevTrend);
            trend = beta * (level - prevLevel) + (1 - beta) * prevTrend;
            seasonal[phase] = gamma * (y[t] - level) + (1 - gamma) * sPrev;
        }

        // --- Forecast: phase keyed off the true grid index of the future day ---
        for (int i = 1; i <= maxH; i++) {
            int phase = (span - 1 + i) % m;
            daily[i - 1] = level + i * trend + seasonal[phase];
        }

        double sigma1 = ResidualIntervals.rmse(resid);
        return WindowForecast.build(
                daily, ctx.horizons(), sigma1, m, ResidualIntervals.Growth.SQRT_H, z, highMax, mediumMax);
    }

    private static int maxHorizon(int[] horizons) {
        int max = 0;
        for (int h : horizons) {
            max = Math.max(max, h);
        }
        return max;
    }
}
