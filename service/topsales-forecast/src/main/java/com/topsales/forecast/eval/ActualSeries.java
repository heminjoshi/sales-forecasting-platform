package com.topsales.forecast.eval;

import com.topsales.common.domain.AggregateRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The full observed history for one {@link SeriesKey}, indexed by date for O(1) zero-filled lookup.
 *
 * <p><b>Zero-fill is load-bearing.</b> A date with no aggregate row is a real no-sale day (a true
 * {@code 0}), not missing data — so {@link #sumOn(LocalDate)} returns {@code 0} there. That is what
 * makes the backtest penalize a forecaster for predicting sales on a day that had none.
 *
 * <p>{@link #trainUpTo(LocalDate)} returns the rows on/before a cutoff and, crucially, <b>anchors</b>
 * the slice to end exactly on the cutoff: if the last real sale falls before {@code trainEnd}, a
 * zero-valued row is appended at {@code trainEnd}. Without this the forecaster's "as-of" would be the
 * last sale date, so its horizon-1 prediction would land on the wrong calendar day for a sparse
 * series and the test alignment (horizon {@code i} ↔ {@code trainEnd + i}) would silently drift.
 */
public final class ActualSeries {

    private final SeriesKey key;
    private final List<AggregateRow> rows; // sorted ascending by bucketDate
    private final Map<LocalDate, BigDecimal> byDate;
    private final String currency;

    private ActualSeries(SeriesKey key, List<AggregateRow> rows, String currency) {
        this.key = key;
        this.rows = rows;
        this.currency = currency;
        this.byDate = new HashMap<>(rows.size() * 2);
        for (AggregateRow r : rows) {
            byDate.merge(r.bucketDate(), r.sumAmount(), BigDecimal::add);
        }
    }

    public static ActualSeries from(SeriesKey key, List<AggregateRow> rawRows) {
        List<AggregateRow> sorted = new ArrayList<>(rawRows);
        sorted.sort(Comparator.comparing(AggregateRow::bucketDate));
        String currency = sorted.isEmpty() ? "USD" : sorted.get(0).currency();
        return new ActualSeries(key, sorted, currency);
    }

    public SeriesKey key() {
        return key;
    }

    /** The observed sum on {@code date}, zero-filled when the series has no row that day. */
    public BigDecimal sumOn(LocalDate date) {
        return byDate.getOrDefault(date, BigDecimal.ZERO);
    }

    /**
     * Training rows with {@code bucketDate ≤ trainEnd}, anchored to end exactly on {@code trainEnd}
     * (a zero row is appended if no real row lands there). Returns an empty list if there is no
     * history at all on/before the cutoff.
     */
    public List<AggregateRow> trainUpTo(LocalDate trainEnd) {
        List<AggregateRow> slice = new ArrayList<>();
        boolean hasEnd = false;
        for (AggregateRow r : rows) {
            if (r.bucketDate().isAfter(trainEnd)) {
                break; // sorted — nothing later qualifies
            }
            slice.add(r);
            if (r.bucketDate().equals(trainEnd)) {
                hasEnd = true;
            }
        }
        if (!slice.isEmpty() && !hasEnd) {
            slice.add(
                    new AggregateRow(
                            key.tenantId(),
                            key.categoryId(),
                            key.channel(),
                            trainEnd,
                            BigDecimal.ZERO,
                            0,
                            currency));
        }
        return slice;
    }
}
