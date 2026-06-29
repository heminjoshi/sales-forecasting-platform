package com.topsales.forecast.math;

import com.topsales.common.domain.AggregateRow;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Regularizes one raw series (the rows for a single {@code (category, channel)}, daily, possibly with
 * gaps) onto a contiguous daily grid {@code [minDate..maxDate]}. Missing days are gap-filled with
 * {@code 0.0} — a no-sale day is a real zero, not unknown data, so the forecasters and the residual
 * model treat it as such (this is what penalizes forecasting sales on a no-sale day).
 *
 * <p>Indexing is <b>by date</b>, not by the caller's array position: index {@code i} of {@link
 * #values()} is always {@code startDate.plusDays(i)}, and the last index is {@link #asOf()}
 * ({@code = maxDate}). That keeps seasonal lookups ("the value {@code m} days ago") correct across
 * gaps. {@code sumAmount} may be signed (returns), so values can be negative.
 *
 * <p>Pure, allocation-light, no Spring. Shared by every {@code model/} forecaster (including the
 * common-deps-only {@code SeasonalNaiveForecaster} reused as the Phase-4 fallback).
 */
public final class SeriesPrep {

    private final LocalDate startDate;
    private final LocalDate asOf;
    private final double[] values;
    private final int nonEmptyDays;

    private SeriesPrep(LocalDate startDate, LocalDate asOf, double[] values, int nonEmptyDays) {
        this.startDate = startDate;
        this.asOf = asOf;
        this.values = values;
        this.nonEmptyDays = nonEmptyDays;
    }

    /**
     * Build the daily grid for {@code history}. An empty (or {@code null}) history yields an empty
     * grid ({@code span() == 0}); callers route that to the flat-prior cold-start branch.
     */
    public static SeriesPrep of(List<AggregateRow> history) {
        if (history == null || history.isEmpty()) {
            return new SeriesPrep(null, null, new double[0], 0);
        }

        LocalDate min = null;
        LocalDate max = null;
        for (AggregateRow row : history) {
            LocalDate d = row.bucketDate();
            if (min == null || d.isBefore(min)) {
                min = d;
            }
            if (max == null || d.isAfter(max)) {
                max = d;
            }
        }

        int span = (int) ChronoUnit.DAYS.between(min, max) + 1;
        double[] grid = new double[span];
        for (AggregateRow row : history) {
            int idx = (int) ChronoUnit.DAYS.between(min, row.bucketDate());
            // Sum defensively: one series is normally one row/day, but never assume it.
            grid[idx] += row.sumAmount() == null ? 0.0 : row.sumAmount().doubleValue();
        }

        int nonEmpty = 0;
        for (double v : grid) {
            if (v != 0.0) {
                nonEmpty++;
            }
        }
        return new SeriesPrep(min, max, grid, nonEmpty);
    }

    /** The gap-filled daily values; index {@code i} is {@code startDate.plusDays(i)}. Live view. */
    public double[] values() {
        return values;
    }

    /** First calendar day of the grid (index 0), or {@code null} for an empty series. */
    public LocalDate startDate() {
        return startDate;
    }

    /** Last calendar day of the grid ({@code = maxDate}), or {@code null} for an empty series. */
    public LocalDate asOf() {
        return asOf;
    }

    /** Grid length in days (inclusive of both endpoints); 0 for an empty series. */
    public int span() {
        return values.length;
    }

    /** Count of grid days with a non-zero value — the "real activity" signal for sparse detection. */
    public int nonEmptyDays() {
        return nonEmptyDays;
    }

    /** Fraction of the grid with activity ({@code nonEmptyDays / span}); 0 for an empty series. */
    public double density() {
        return values.length == 0 ? 0.0 : (double) nonEmptyDays / values.length;
    }
}
