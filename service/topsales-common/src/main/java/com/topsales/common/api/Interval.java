package com.topsales.common.api;

import java.math.BigDecimal;

/** Forecast prediction interval. Absent (null, omitted) in actuals/pending responses. */
public record Interval(BigDecimal low, BigDecimal high) {
}
