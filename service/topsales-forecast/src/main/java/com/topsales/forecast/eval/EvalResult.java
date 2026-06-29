package com.topsales.forecast.eval;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * The full backtest outcome — everything {@link EvalReport} needs to render deterministically.
 *
 * @param segments per {@code (series, forecaster)} metrics (Section A)
 * @param comparisons per-series naive-vs-Holt-Winters side-by-side with a winner (Section B)
 * @param overall per-forecaster pooled rollup over all defined segments (Section C)
 * @param cv the CV parameters used
 * @param foldCount number of folds actually run
 * @param windowStart first day of the (fixed) backtest window
 * @param windowEnd last day of the (fixed) backtest window
 */
public record EvalResult(
        List<SegmentMetrics> segments,
        List<Comparison> comparisons,
        List<PooledMetrics> overall,
        CvConfig cv,
        int foldCount,
        LocalDate windowStart,
        LocalDate windowEnd) {

    /** A forecaster's pooled accuracy across every defined series. */
    public record PooledMetrics(String model, double wape, double bias, int points, int segments) {}

    /** One series scored by both forecasters, with the lower-WAPE winner. */
    public record Comparison(SeriesKey key, SegmentMetrics naive, SegmentMetrics holtWinters, String winner) {}

    /** Look up one segment by series + model (used by the regression test). */
    public Optional<SegmentMetrics> segment(SeriesKey key, String model) {
        return segments.stream().filter(s -> s.key().equals(key) && s.model().equals(model)).findFirst();
    }

    /** Look up a forecaster's pooled rollup by model name. */
    public Optional<PooledMetrics> pooled(String model) {
        return overall.stream().filter(p -> p.model().equals(model)).findFirst();
    }
}
