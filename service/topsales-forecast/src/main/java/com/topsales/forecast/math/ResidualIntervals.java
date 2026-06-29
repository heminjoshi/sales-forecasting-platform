package com.topsales.forecast.math;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prediction-interval math: an in-sample one-step residual scale ({@link #rmse}) grown to a
 * window-sum scale ({@link #sigmaH}) and turned into money-rounded bounds ({@link #bounds}).
 *
 * <p><b>Window-sum growth.</b> A horizon's {@code pointValue} is the predicted <em>sum</em> of the
 * next {@code h} days, so its uncertainty grows with {@code h}. We use a defensible √-growth on the
 * one-step σ:
 *
 * <ul>
 *   <li>{@link Growth#NAIVE} — {@code σ·√(h·(1+⌊(h-1)/m⌋))}: seasonal-naive re-uses each season's
 *       value, so error compounds once per completed season, not every day.
 *   <li>{@link Growth#SQRT_H} — {@code σ·√h}: the textbook random-walk-of-errors growth for the
 *       smoothing models.
 * </ul>
 *
 * <p>Rounding to money (scale 2) happens only here, at the boundary: the point is {@code HALF_UP},
 * the low is {@code FLOOR} and the high is {@code CEILING} so the rounding can only widen the band —
 * guaranteeing {@code low ≤ point ≤ high}.
 */
public final class ResidualIntervals {

    private ResidualIntervals() {}

    /** Which √-growth law to apply to the one-step σ when extending it to an {@code h}-day sum. */
    public enum Growth {
        NAIVE,
        SQRT_H
    }

    /** Root-mean-square of in-sample one-step residuals; {@code 0.0} when there are fewer than two. */
    public static double rmse(double[] residuals) {
        if (residuals == null || residuals.length < 2) {
            return 0.0;
        }
        double ss = 0.0;
        for (double e : residuals) {
            ss += e * e;
        }
        return Math.sqrt(ss / residuals.length);
    }

    /** Grow a one-step σ to the σ of an {@code h}-day window sum under the chosen {@link Growth}. */
    public static double sigmaH(double sigma1, int h, int season, Growth growth) {
        if (h <= 0 || sigma1 <= 0.0) {
            return 0.0;
        }
        double factor =
                switch (growth) {
                    case SQRT_H -> Math.sqrt(h);
                    case NAIVE -> {
                        int m = Math.max(season, 1);
                        yield Math.sqrt((double) h * (1 + (h - 1) / m));
                    }
                };
        return sigma1 * factor;
    }

    /**
     * Money-rounded bounds for {@code point ± z·σ_h}, with {@code low ≤ point ≤ high} guaranteed.
     */
    public static Bounds bounds(double point, double sigmaH, double z) {
        double half = Math.abs(z * sigmaH);
        BigDecimal p = money(point, RoundingMode.HALF_UP);
        BigDecimal low = money(point - half, RoundingMode.FLOOR);
        BigDecimal high = money(point + half, RoundingMode.CEILING);
        // Defend against rounding nudging the point outside its own (possibly zero-width) band.
        if (low.compareTo(p) > 0) {
            low = p;
        }
        if (high.compareTo(p) < 0) {
            high = p;
        }
        return new Bounds(low, p, high);
    }

    /** Round a raw {@code double} to a 2-dp money {@link BigDecimal} under {@code mode}. */
    public static BigDecimal money(double value, RoundingMode mode) {
        return BigDecimal.valueOf(value).setScale(2, mode);
    }

    /** A money-rounded prediction interval: {@code low ≤ point ≤ high}. */
    public record Bounds(BigDecimal low, BigDecimal point, BigDecimal high) {}
}
