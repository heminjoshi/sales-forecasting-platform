package com.topsales.common.api;

import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

/**
 * A validated read request: the tenant (from auth, §11), window, mode, and k. The single coupling
 * argument between the read path and the serving/forecast plane ({@code ForecastProvider}).
 */
public record TopKQuery(
        String tenantId,
        Window window,
        Mode mode,
        int k) {
}
