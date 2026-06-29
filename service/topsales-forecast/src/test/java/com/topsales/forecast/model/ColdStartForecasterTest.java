package com.topsales.forecast.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.forecast.Series;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Branch coverage for the cold-start dispatcher: each shape routes to the documented model. */
class ColdStartForecasterTest {

    private static final int M = 7;

    private final ColdStartForecaster dispatcher =
            new ColdStartForecaster(
                    M,
                    0.4,
                    new FlatPriorForecaster(),
                    new SparseRateForecaster(1.28, 0.15, 0.40),
                    new TrendOnlyForecaster(0.3, 0.1, 1.28, 0.15, 0.40),
                    new SeasonalNaiveForecaster(M, 1.28, 0.15, 0.40),
                    new HoltWintersForecaster(0.3, 0.1, 0.3, M, 1.28, 0.15, 0.40));

    @Test
    void emptySeriesRoutesToFlatPriorLow() {
        List<AggregateRow> history = List.of();
        assertThat(dispatcher.select(history)).isEqualTo(ColdStartForecaster.Model.FLAT_PRIOR);
        assertThat(confidence(history)).isEqualTo(Confidence.LOW);
    }

    @Test
    void shortSeriesRoutesToTrendOnlyCappedMedium() {
        // 4 dense days (span < m).
        List<AggregateRow> history = Series.daily(100, 110, 120, 130);
        assertThat(dispatcher.select(history)).isEqualTo(ColdStartForecaster.Model.TREND_ONLY);
        // A clean trend would score HIGH on its own; the dispatcher caps it at MEDIUM.
        assertThat(confidence(history)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void mediumSeriesRoutesToSeasonalNaiveCappedMedium() {
        // 10 dense days (m ≤ span < 2m).
        List<AggregateRow> history = Series.daily(100, 100, 100, 100, 100, 100, 100, 100, 100, 100);
        assertThat(dispatcher.select(history)).isEqualTo(ColdStartForecaster.Model.SEASONAL_NAIVE);
        assertThat(confidence(history)).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void longDenseSeriesRoutesToHoltWinters() {
        // 21 dense days (span ≥ 2m).
        double[] y = new double[21];
        for (int t = 0; t < 21; t++) {
            y[t] = 100 + (t % M);
        }
        List<AggregateRow> history = Series.daily(y);
        assertThat(dispatcher.select(history)).isEqualTo(ColdStartForecaster.Model.HOLT_WINTERS);
    }

    @Test
    void intermittentSeriesRoutesToSparseRateBeforeLength() {
        // 20 days, only 3 with activity (density 0.15) — sparse must win over the length branches.
        List<AggregateRow> history = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            double amount = (i == 2 || i == 9 || i == 17) ? 500 : 0;
            history.add(Series.row(Series.START.plusDays(i), amount));
        }
        assertThat(dispatcher.select(history)).isEqualTo(ColdStartForecaster.Model.SPARSE_RATE);
        assertThat(confidence(history)).isEqualTo(Confidence.LOW);
    }

    private Confidence confidence(List<AggregateRow> history) {
        List<ForecastValue> out = dispatcher.forecast(history, Series.ctx(7));
        return out.get(0).confidence();
    }
}
