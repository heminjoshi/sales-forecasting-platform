package com.topsales.forecast.math;

import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ForecastValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Assembles a model's per-day forecast curve into the per-horizon {@link ForecastValue} list the
 * {@code Forecaster} contract requires. For each horizon {@code h} the {@code pointValue} is the
 * <b>sum of the next {@code h} days</b> (a cumulative window total, directly comparable to the
 * read-path's trailing-window actuals), the interval comes from {@link ResidualIntervals}, and the
 * confidence from {@link ConfidenceMapper}. Output order matches the input {@code horizons}.
 *
 * <p>This is the single place the five {@code model/} forecasters share, so the summation, interval
 * growth, rounding, and confidence policy stay identical across them.
 */
public final class WindowForecast {

    private WindowForecast() {}

    /**
     * Build the per-horizon outputs from a daily forecast curve.
     *
     * @param dailyForecast {@code dailyForecast[i] = ŷ(asOf + i + 1)}; must be at least
     *     {@code max(horizons)} long
     * @param sigma1 the model's in-sample one-step residual σ
     * @param season season length (used only by {@link ResidualIntervals.Growth#NAIVE})
     */
    public static List<ForecastValue> build(
            double[] dailyForecast,
            int[] horizons,
            double sigma1,
            int season,
            ResidualIntervals.Growth growth,
            double z,
            double highMax,
            double mediumMax) {

        List<ForecastValue> out = new ArrayList<>(horizons.length);
        for (int h : horizons) {
            double point = 0.0;
            int limit = Math.min(h, dailyForecast.length);
            for (int i = 0; i < limit; i++) {
                point += dailyForecast[i];
            }
            double sigmaH = ResidualIntervals.sigmaH(sigma1, h, season, growth);
            double halfWidth = z * sigmaH;
            ResidualIntervals.Bounds b = ResidualIntervals.bounds(point, sigmaH, z);
            Confidence c = ConfidenceMapper.classify(point, halfWidth, highMax, mediumMax);
            out.add(new ForecastValue(h, b.point(), b.low(), b.high(), c));
        }
        return out;
    }

    /**
     * Return a copy of {@code values} with each confidence capped so it is <b>no stronger</b> than
     * {@code max}. The cold-start dispatcher uses this to express its per-branch confidence ceiling
     * (e.g. a short-history seasonal-naive can never claim {@code HIGH}) without the sub-models
     * needing to know they were chosen as a fallback.
     */
    public static List<ForecastValue> cap(List<ForecastValue> values, Confidence max) {
        List<ForecastValue> out = new ArrayList<>(values.size());
        for (ForecastValue v : values) {
            // Confidence ordinals run HIGH(0) → MEDIUM(1) → LOW(2): "no stronger than max" is the
            // larger ordinal.
            Confidence c = v.confidence().ordinal() < max.ordinal() ? max : v.confidence();
            out.add(new ForecastValue(v.horizon(), v.pointValue(), v.intervalLow(), v.intervalHigh(), c));
        }
        return out;
    }
}
