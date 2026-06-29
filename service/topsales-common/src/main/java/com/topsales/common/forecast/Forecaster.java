package com.topsales.common.forecast;

import com.topsales.common.domain.AggregateRow;

import java.util.List;

/**
 * Pluggable forecasting model — a pure function of history to predicted values, no I/O. The seam
 * that lets baseline (SeasonalNaive/HoltWinters) and ML (SageMaker, designed) swap without touching
 * the batch or read paths (DR-4). Impls land in topsales-forecast (Phase 3).
 */
public interface Forecaster {
    List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx);
}
