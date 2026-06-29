package com.topsales.common.forecast;

import com.topsales.common.domain.Confidence;

import java.math.BigDecimal;

/** A forecaster's output for one horizon: point estimate, prediction interval, and confidence. */
public record ForecastValue(
        int horizon,
        BigDecimal pointValue,
        BigDecimal intervalLow,
        BigDecimal intervalHigh,
        Confidence confidence) {
}
