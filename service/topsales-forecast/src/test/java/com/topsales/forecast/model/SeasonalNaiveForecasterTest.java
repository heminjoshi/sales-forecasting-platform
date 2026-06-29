package com.topsales.forecast.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.forecast.Series;

import java.util.List;

import org.junit.jupiter.api.Test;

class SeasonalNaiveForecasterTest {

    private static final int M = 7;
    private final SeasonalNaiveForecaster forecaster =
            new SeasonalNaiveForecaster(M, 1.28, 0.15, 0.40);

    @Test
    void pointIsSumOfTheValuesFromMDaysAgo() {
        // 14 contiguous days: 1..14. The last 7 days are 8..14 (sum 77).
        List<AggregateRow> history = Series.daily(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14);

        List<ForecastValue> out = forecaster.forecast(history, Series.ctx(7, 14));

        // h=7: ŷ over the next 7 days = the last 7 observed (8..14).
        assertThat(out.get(0).horizon()).isEqualTo(7);
        assertThat(out.get(0).pointValue()).isEqualByComparingTo("77.00");
        // h=14: the season repeats once, so 2 × 77.
        assertThat(out.get(1).horizon()).isEqualTo(14);
        assertThat(out.get(1).pointValue()).isEqualByComparingTo("154.00");
        // Interval brackets the point.
        assertThat(out.get(0).intervalLow()).isLessThanOrEqualTo(out.get(0).pointValue());
        assertThat(out.get(0).intervalHigh()).isGreaterThanOrEqualTo(out.get(0).pointValue());
    }

    @Test
    void gapsAreRealZeros() {
        // 14 days all worth 10, but day index 10 is missing → gap-filled to 0.
        List<AggregateRow> history = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            if (i == 10) {
                continue;
            }
            history.add(Series.row(Series.START.plusDays(i), 10));
        }

        List<ForecastValue> out = forecaster.forecast(history, Series.ctx(7));

        // Last 7 grid days are indices 7..13 = [10,10,10,0,10,10,10] → 60.
        assertThat(out.get(0).pointValue()).isEqualByComparingTo("60.00");
    }

    @Test
    void signedReturnsFlowThroughAsNegatives() {
        // Last 7 days include a -20 return; sum of last 7 = 10*6 - 20 = 40.
        List<AggregateRow> history =
                Series.daily(10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10, -20);

        List<ForecastValue> out = forecaster.forecast(history, Series.ctx(7));

        assertThat(out.get(0).pointValue()).isEqualByComparingTo("40.00");
        assertThat(out.get(0).intervalLow()).isLessThanOrEqualTo(out.get(0).pointValue());
        assertThat(out.get(0).intervalHigh()).isGreaterThanOrEqualTo(out.get(0).pointValue());
    }
}
