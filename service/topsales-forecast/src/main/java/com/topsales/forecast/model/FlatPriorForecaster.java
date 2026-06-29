package com.topsales.forecast.model;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.forecast.math.ResidualIntervals;
import com.topsales.forecast.math.SeriesPrep;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Last-resort prior for a series with no real activity ({@code n == 0}): repeat the last observed
 * value (or {@code 0} when there is no history) for every future day, so {@code pointValue(h) =
 * last · h}. There is no dispersion to model, so the interval collapses to the point and confidence
 * is always {@code LOW} — the dashboard still renders something honest rather than failing.
 */
public final class FlatPriorForecaster implements Forecaster {

    @Override
    public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
        SeriesPrep prep = SeriesPrep.of(history);
        double[] y = prep.values();
        int span = prep.span();
        double last = span == 0 ? 0.0 : y[span - 1];

        List<ForecastValue> out = new ArrayList<>(ctx.horizons().length);
        for (int h : ctx.horizons()) {
            var point = ResidualIntervals.money(last * h, RoundingMode.HALF_UP);
            // No residual model → degenerate interval; LOW carries the cold-start signal.
            out.add(new ForecastValue(h, point, point, point, Confidence.LOW));
        }
        return out;
    }
}
