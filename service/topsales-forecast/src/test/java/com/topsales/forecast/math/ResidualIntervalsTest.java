package com.topsales.forecast.math;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.forecast.math.ResidualIntervals.Bounds;
import com.topsales.forecast.math.ResidualIntervals.Growth;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

class ResidualIntervalsTest {

    private static final Offset<Double> EPS = Offset.offset(1e-9);

    @Test
    void rmseIsRootMeanSquare() {
        // sqrt((9 + 16) / 2) = sqrt(12.5).
        assertThat(ResidualIntervals.rmse(new double[] {3, -4})).isCloseTo(Math.sqrt(12.5), EPS);
    }

    @Test
    void rmseIsZeroForFewerThanTwoResiduals() {
        assertThat(ResidualIntervals.rmse(new double[] {5})).isZero();
        assertThat(ResidualIntervals.rmse(new double[0])).isZero();
    }

    @Test
    void sqrtHGrowthScalesWithRootH() {
        assertThat(ResidualIntervals.sigmaH(2.0, 9, 7, Growth.SQRT_H)).isCloseTo(2.0 * 3.0, EPS);
    }

    @Test
    void naiveGrowthStepsOncePerCompletedSeason() {
        // h=7, m=7 → sqrt(7 * (1 + 0)) = sqrt(7).
        assertThat(ResidualIntervals.sigmaH(1.0, 7, 7, Growth.NAIVE)).isCloseTo(Math.sqrt(7), EPS);
        // h=8, m=7 → sqrt(8 * (1 + 1)) = sqrt(16) = 4.
        assertThat(ResidualIntervals.sigmaH(1.0, 8, 7, Growth.NAIVE)).isCloseTo(4.0, EPS);
    }

    @Test
    void boundsBracketThePointAndRoundOutward() {
        Bounds b = ResidualIntervals.bounds(100.0, 10.0, 1.28); // half-width 12.8
        assertThat(b.point()).isEqualByComparingTo("100.00");
        assertThat(b.low()).isLessThanOrEqualTo(b.point());
        assertThat(b.high()).isGreaterThanOrEqualTo(b.point());
        // FLOOR low ≤ 87.2, CEIL high ≥ 112.8.
        assertThat(b.low().doubleValue()).isLessThanOrEqualTo(87.2);
        assertThat(b.high().doubleValue()).isGreaterThanOrEqualTo(112.8);
    }

    @Test
    void zeroSigmaCollapsesToThePoint() {
        Bounds b = ResidualIntervals.bounds(250.0, 0.0, 1.28);
        assertThat(b.low()).isLessThanOrEqualTo(b.point());
        assertThat(b.high()).isGreaterThanOrEqualTo(b.point());
    }
}
