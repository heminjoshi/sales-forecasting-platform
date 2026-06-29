package com.topsales.common.forecast;

import com.topsales.common.domain.Confidence;

import java.math.BigDecimal;

/**
 * One precomputed, ranked serving row (mirror of the serving_rows table, §2). Interval/confidence/
 * delta are forecast-only and may be null. Written by the forecast batch (Phase 3).
 */
public record ServingRow(
        int rank,
        String categoryId,
        BigDecimal value,
        BigDecimal intervalLow,
        BigDecimal intervalHigh,
        Confidence confidence,
        BigDecimal deltaVsPrior) {
}
