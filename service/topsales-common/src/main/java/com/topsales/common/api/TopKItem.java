package com.topsales.common.api;

import com.topsales.common.domain.Confidence;

import java.math.BigDecimal;

/**
 * One ranked category in a top-k response (§13). {@code deltaVsPrior}, {@code confidence}, and
 * {@code interval} are forecast-only — null (omitted) in actuals/pending, where the UI hides those
 * columns. {@code deltaVsPrior} is a fraction, e.g. 0.12 = +12%.
 */
public record TopKItem(
        int rank,
        String category,
        BigDecimal value,
        BigDecimal deltaVsPrior,
        Confidence confidence,
        Interval interval) {
}
