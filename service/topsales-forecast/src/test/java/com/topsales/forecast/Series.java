package com.topsales.forecast;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.domain.Window;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Test fixtures: build daily {@link AggregateRow} series and forecast contexts deterministically. */
public final class Series {

    /** A fixed anchor so tests don't depend on the wall clock. */
    public static final LocalDate START = LocalDate.of(2025, 1, 6); // a Monday

    private Series() {}

    /** One contiguous daily series starting at {@link #START}, value[i] on day START+i. */
    public static List<AggregateRow> daily(double... values) {
        List<AggregateRow> rows = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            rows.add(row(START.plusDays(i), values[i]));
        }
        return rows;
    }

    /** A single row for an explicit date (used to build series with gaps). */
    public static AggregateRow row(LocalDate date, double amount) {
        return new AggregateRow(
                "t1", "cat-1", Channel.ONLINE, date, BigDecimal.valueOf(amount), 1, "USD");
    }

    /** A {@link ForecastContext} carrying the given horizons (window is ignored by the math). */
    public static ForecastContext ctx(int... horizons) {
        return new ForecastContext("t1", "cat-1", horizons, Window.WEEK);
    }
}
