package com.topsales.common.forecast;

import com.topsales.common.api.TopKQuery;

import java.util.Optional;

/**
 * Versioned serving-table reader — the ONLY coupling between the forecast and read planes. The seam
 * that lets precompute ↔ on-demand ↔ hybrid swap without touching the read path (DR-1). An empty
 * result triggers the read-path degradation chain (Phase 4). Built impl: PrecomputedForecastProvider.
 */
public interface ForecastProvider {
    Optional<ServingResult> getTopK(TopKQuery query);
}
