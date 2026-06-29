package com.topsales.forecast.math;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Confidence;

import org.junit.jupiter.api.Test;

class ConfidenceMapperTest {

    private static final double HIGH_MAX = 0.15;
    private static final double MED_MAX = 0.40;

    @Test
    void tightBandIsHigh() {
        // r = 50 / 1000 = 0.05 < 0.15.
        assertThat(ConfidenceMapper.classify(1000, 50, HIGH_MAX, MED_MAX)).isEqualTo(Confidence.HIGH);
    }

    @Test
    void mediumBandIsMedium() {
        // r = 300 / 1000 = 0.30.
        assertThat(ConfidenceMapper.classify(1000, 300, HIGH_MAX, MED_MAX))
                .isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void wideBandIsLow() {
        // r = 500 / 1000 = 0.50 ≥ 0.40.
        assertThat(ConfidenceMapper.classify(1000, 500, HIGH_MAX, MED_MAX)).isEqualTo(Confidence.LOW);
    }

    @Test
    void nearZeroPointIsNeverHigh() {
        // Even with a vanishing band, a near-zero point cannot be HIGH.
        assertThat(ConfidenceMapper.classify(0.0, 0.0, HIGH_MAX, MED_MAX)).isNotEqualTo(Confidence.HIGH);
        assertThat(ConfidenceMapper.classify(0.2, 0.001, HIGH_MAX, MED_MAX))
                .isNotEqualTo(Confidence.HIGH);
    }
}
