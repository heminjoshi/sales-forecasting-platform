/**
 * The {@code Forecaster} model family — the swappable baseline implementations behind the
 * {@code com.topsales.common.forecast.Forecaster} seam, plus the {@link
 * com.topsales.forecast.model.ColdStartForecaster} dispatcher that routes a series to the right one.
 *
 * <p>Built (🔨): {@link com.topsales.forecast.model.SeasonalNaiveForecaster} (also the Phase-4
 * degradation fallback — kept {@code topsales-common}-deps-only), {@link
 * com.topsales.forecast.model.HoltWintersForecaster} (additive), {@link
 * com.topsales.forecast.model.TrendOnlyForecaster}, {@link
 * com.topsales.forecast.model.SparseRateForecaster}, {@link
 * com.topsales.forecast.model.FlatPriorForecaster}.
 *
 * <p>Designed-only (📐) upgrades behind this <b>same</b> seam — intentionally not built here:
 *
 * <ul>
 *   <li>a {@code CrostonForecaster} for intermittent demand, a principled replacement for the
 *       sparse mean-rate branch (ADR-0004);
 *   <li>a SageMaker-backed {@code Forecaster} (a global ML model) promoted per-segment once accuracy
 *       data justifies it, swapped in via Spring profile without touching the batch or read paths
 *       (ADR-0004 / ADR-0005).
 * </ul>
 */
package com.topsales.forecast.model;
