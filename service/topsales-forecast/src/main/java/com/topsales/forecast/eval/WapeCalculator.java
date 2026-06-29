package com.topsales.forecast.eval;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Accumulates {@code (actual, forecast)} day-pairs and computes WAPE + bias by the <b>ratio-of-sums</b>
 * (pooled / volume-weighted) definition — <em>not</em> a mean of per-day ratios:
 *
 * <pre>
 *   WAPE = Σ|a − f| / Σ|a|        bias = Σ(f − a) / Σ|a|
 * </pre>
 *
 * <p>The error sums are kept in {@link BigDecimal} (the inputs are exact money values) and converted
 * to {@code double} only at the edge, in {@link #toMetrics}. Pooling at a coarser grain is just
 * {@link #merge(WapeCalculator)} of finer accumulators — the same ratio-of-sums, so it composes.
 *
 * <p><b>Near-zero guard:</b> when {@code Σ|a| == 0} the ratios are undefined; the segment is marked
 * {@code defined=false} (and a caller excludes it from any rollup).
 */
public final class WapeCalculator {

    /** Division precision for the final ratio; the report rounds further at format time. */
    private static final MathContext MC = MathContext.DECIMAL64;

    private BigDecimal sumAbsErr = BigDecimal.ZERO; // Σ|a − f|
    private BigDecimal sumAbsActual = BigDecimal.ZERO; // Σ|a|
    private BigDecimal sumSignedErr = BigDecimal.ZERO; // Σ(f − a)
    private int n = 0;

    /** Add one scored day-pair. */
    public void add(BigDecimal actual, BigDecimal forecast) {
        BigDecimal err = forecast.subtract(actual); // f − a
        sumAbsErr = sumAbsErr.add(err.abs());
        sumSignedErr = sumSignedErr.add(err);
        sumAbsActual = sumAbsActual.add(actual.abs());
        n++;
    }

    /** Fold another accumulator's sums into this one (used to pool segments → overall). */
    public void merge(WapeCalculator other) {
        sumAbsErr = sumAbsErr.add(other.sumAbsErr);
        sumSignedErr = sumSignedErr.add(other.sumSignedErr);
        sumAbsActual = sumAbsActual.add(other.sumAbsActual);
        n += other.n;
    }

    public int count() {
        return n;
    }

    /** False when {@code Σ|a| == 0} — the ratios would be undefined. */
    public boolean defined() {
        return sumAbsActual.signum() != 0;
    }

    public double wape() {
        return defined() ? sumAbsErr.divide(sumAbsActual, MC).doubleValue() : Double.NaN;
    }

    public double bias() {
        return defined() ? sumSignedErr.divide(sumAbsActual, MC).doubleValue() : Double.NaN;
    }

    public SegmentMetrics toMetrics(SeriesKey key, String model) {
        return new SegmentMetrics(key, model, wape(), bias(), n, defined());
    }
}
