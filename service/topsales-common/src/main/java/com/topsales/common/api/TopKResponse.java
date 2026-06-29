package com.topsales.common.api;

import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;

import java.time.Instant;
import java.util.List;

/**
 * The read response the dashboard renders (§13). Always carries a {@link Status} and an
 * {@code asOf} — reads never fail closed. {@code channel} echoes the requested view (ADR-0010).
 * {@code insight} is the grounded NL line (Phase 5); a deterministic template is the floor, so it is
 * never null at runtime.
 */
public record TopKResponse(
        String tenantId,
        Mode mode,
        Window window,
        ChannelFilter channel,
        int k,
        Status status,
        Instant asOf,
        String insight,
        List<TopKItem> items) {
}
