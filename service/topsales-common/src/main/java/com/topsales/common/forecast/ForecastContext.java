package com.topsales.common.forecast;

import com.topsales.common.domain.Window;

/** What a {@link Forecaster} needs beyond the history: which series and which horizons to predict. */
public record ForecastContext(
        String tenantId,
        String categoryId,
        int[] horizons,
        Window window) {
}
