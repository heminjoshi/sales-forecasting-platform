package com.topsales.forecast.model;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.forecast.math.SeriesPrep;
import com.topsales.forecast.math.WindowForecast;

import java.util.List;

/**
 * Cold-start dispatcher: routes one series to the model that suits its shape, so each underlying
 * forecaster stays a clean textbook implementation and the batch wires exactly one {@link
 * Forecaster} bean. Routing is by {@code n} (non-empty days), {@code span}, {@code density}, and the
 * season length {@code m}:
 *
 * <ol>
 *   <li>{@code n == 0} → {@link FlatPriorForecaster} (LOW)
 *   <li>{@code density < sparseDensityMax} → {@link SparseRateForecaster} (LOW) — <b>evaluated
 *       before length</b>, so an intermittent series never reaches the seasonal/HW branches
 *   <li>{@code span < m} → {@link TrendOnlyForecaster} (capped MEDIUM)
 *   <li>{@code m ≤ span < 2m} → {@link SeasonalNaiveForecaster} (capped MEDIUM)
 *   <li>{@code span ≥ 2m} and dense → {@link HoltWintersForecaster} (interval-derived HIGH/MED/LOW)
 * </ol>
 *
 * <p>Cold-start surfaces purely through {@link Confidence} (no flag field): the short-history
 * branches are capped so they can never claim {@code HIGH}, and the sparse/flat branches are always
 * {@code LOW}.
 */
public final class ColdStartForecaster implements Forecaster {

    /** The model a series routes to — exposed so the batch/tests can assert routing decisions. */
    public enum Model {
        FLAT_PRIOR,
        SPARSE_RATE,
        TREND_ONLY,
        SEASONAL_NAIVE,
        HOLT_WINTERS
    }

    private final int seasonLength;
    private final double sparseDensityMax;
    private final FlatPriorForecaster flatPrior;
    private final SparseRateForecaster sparseRate;
    private final TrendOnlyForecaster trendOnly;
    private final SeasonalNaiveForecaster seasonalNaive;
    private final HoltWintersForecaster holtWinters;

    public ColdStartForecaster(
            int seasonLength,
            double sparseDensityMax,
            FlatPriorForecaster flatPrior,
            SparseRateForecaster sparseRate,
            TrendOnlyForecaster trendOnly,
            SeasonalNaiveForecaster seasonalNaive,
            HoltWintersForecaster holtWinters) {
        this.seasonLength = Math.max(seasonLength, 1);
        this.sparseDensityMax = sparseDensityMax;
        this.flatPrior = flatPrior;
        this.sparseRate = sparseRate;
        this.trendOnly = trendOnly;
        this.seasonalNaive = seasonalNaive;
        this.holtWinters = holtWinters;
    }

    /** Decide which model a series routes to, without running it. */
    public Model select(List<AggregateRow> history) {
        SeriesPrep prep = SeriesPrep.of(history);
        int n = prep.nonEmptyDays();
        int span = prep.span();
        double density = prep.density();
        int m = seasonLength;

        if (n == 0) {
            return Model.FLAT_PRIOR;
        }
        if (density < sparseDensityMax) {
            return Model.SPARSE_RATE; // sparse before length
        }
        if (span < m) {
            return Model.TREND_ONLY;
        }
        if (span < 2 * m) {
            return Model.SEASONAL_NAIVE;
        }
        return Model.HOLT_WINTERS;
    }

    @Override
    public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
        return switch (select(history)) {
            case FLAT_PRIOR -> flatPrior.forecast(history, ctx); // already LOW
            case SPARSE_RATE -> sparseRate.forecast(history, ctx); // already LOW
            case TREND_ONLY ->
                    WindowForecast.cap(trendOnly.forecast(history, ctx), Confidence.MEDIUM);
            case SEASONAL_NAIVE ->
                    WindowForecast.cap(seasonalNaive.forecast(history, ctx), Confidence.MEDIUM);
            case HOLT_WINTERS -> holtWinters.forecast(history, ctx); // full HIGH/MED/LOW
        };
    }
}
