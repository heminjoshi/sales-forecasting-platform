package com.topsales.forecast.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.forecast.ForecastValue;
import com.topsales.forecast.Series;

import java.util.List;

import org.junit.jupiter.api.Test;

class HoltWintersForecasterTest {

    private static final int M = 7;
    private final HoltWintersForecaster forecaster =
            new HoltWintersForecaster(0.3, 0.1, 0.3, M, 1.28, 0.15, 0.40);

    /** Seasonal offsets summing to zero, so the additive level absorbs nothing extra. */
    private static final double[] S = {3, -2, 5, -1, 4, -6, -3};

    @Test
    void reproducesAConstantTrendAdditiveSeriesExactly() {
        // y[t] = 100 + 2t + S[t % 7], 4 full seasons (28 days). With the fixed-point init this is
        // reproduced exactly, so the cumulative-sum forecast is closed-form.
        double L = 100;
        double T = 2;
        double[] y = new double[28];
        for (int t = 0; t < 28; t++) {
            y[t] = L + T * t + S[t % M];
        }

        List<ForecastValue> out = forecaster.forecast(Series.daily(y), Series.ctx(1, 7));

        // h=1: value at grid index 28 = 100 + 2*28 + S[0] = 159.
        assertThat(out.get(0).pointValue().doubleValue()).isCloseTo(159.0, within());
        // h=7: sum over indices 28..34 = 7*100 + 2*(28+..+34) + 0 = 700 + 434 = 1134.
        assertThat(out.get(1).pointValue().doubleValue()).isCloseTo(1134.0, within());
    }

    @Test
    void handlesZeroAndNegativeValuesWithoutNaN() {
        // Additive HW must stay finite across no-sale (0) and return (negative) days.
        double[] y = new double[21];
        for (int t = 0; t < 21; t++) {
            y[t] = (t % 3 == 0) ? 0.0 : (t % 5 == 0 ? -15.0 : 30.0);
        }

        List<ForecastValue> out = forecaster.forecast(Series.daily(y), Series.ctx(7, 30));

        assertThat(out).hasSize(2);
        for (ForecastValue v : out) {
            assertThat(v.pointValue()).isNotNull();
            assertThat(v.pointValue().doubleValue()).isFinite();
            assertThat(v.intervalLow()).isLessThanOrEqualTo(v.pointValue());
            assertThat(v.intervalHigh()).isGreaterThanOrEqualTo(v.pointValue());
            assertThat(v.confidence()).isNotNull();
        }
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.01);
    }
}
