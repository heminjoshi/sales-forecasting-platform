package com.topsales.common.api;

import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Window;

/**
 * A validated read request: the tenant (from auth, §11), window, mode, k, and channel selector. The
 * single coupling argument between the read path and the serving/forecast plane
 * ({@code ForecastProvider}). {@code channel=ALL} is the summed rollup (ADR-0010).
 */
public record TopKQuery(
        String tenantId,
        Window window,
        Mode mode,
        int k,
        ChannelFilter channel) {
}
