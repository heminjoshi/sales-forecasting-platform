package com.topsales.forecast.eval;

/**
 * The scored accuracy of one {@code (series, forecaster)} segment, pooled (ratio-of-sums) over every
 * fold and test day:
 *
 * <ul>
 *   <li>{@code wape} — {@code Σ|a−f| / Σ|a|}, volume-weighted error (0 is perfect).
 *   <li>{@code bias} — {@code Σ(f−a) / Σ|a|}, signed (positive = over-forecast).
 *   <li>{@code n} — number of {@code (actual, forecast)} day-pairs scored.
 *   <li>{@code defined} — false when {@code Σ|a| == 0} (a no-volume segment); such segments are
 *       excluded from the pooled rollups, and {@code wape}/{@code bias} are {@code NaN}.
 * </ul>
 *
 * <p>A 📐 Micrometer seam (gauge {@code forecast.wape{tenant,category,channel,model}}) is noted here
 * for Phase 6 — not wired now (no dependency added).
 */
public record SegmentMetrics(
        SeriesKey key, String model, double wape, double bias, int n, boolean defined) {}
