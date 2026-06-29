package com.topsales.forecast.math;

import com.topsales.common.domain.Confidence;

/**
 * Maps a prediction interval's relative half-width to a {@link Confidence} band — the user-facing
 * "how much to trust this number" signal (and the carrier for cold-start, which surfaces as
 * {@code LOW} rather than a separate flag).
 *
 * <p>{@code r = halfWidth / max(|point|, ε)} — a tight band relative to the prediction is confident:
 * {@code r < highMax → HIGH}, {@code r < mediumMax → MEDIUM}, else {@code LOW}. A near-zero point can
 * never be {@code HIGH}: a tiny denominator would otherwise make even a meaningless band look
 * "confident", so points below {@link #NEAR_ZERO} are capped at {@code MEDIUM}.
 */
public final class ConfidenceMapper {

    private ConfidenceMapper() {}

    /** Division guard so a zero point can't blow up the ratio. */
    static final double EPSILON = 1e-9;

    /** A point this small (in money units) is treated as "near zero" and can never be {@code HIGH}. */
    static final double NEAR_ZERO = 1.0;

    /**
     * Classify {@code point ± halfWidth} (both raw doubles, pre-rounding) into a confidence band.
     *
     * @param halfWidth the interval half-width {@code z·σ_h} (non-negative)
     */
    public static Confidence classify(double point, double halfWidth, double highMax, double mediumMax) {
        double abs = Math.abs(point);
        double r = Math.abs(halfWidth) / Math.max(abs, EPSILON);
        if (abs < NEAR_ZERO) {
            // Honest near-zero prediction: tight → MEDIUM, wide → LOW, but never HIGH.
            return r < mediumMax ? Confidence.MEDIUM : Confidence.LOW;
        }
        if (r < highMax) {
            return Confidence.HIGH;
        }
        if (r < mediumMax) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }
}
