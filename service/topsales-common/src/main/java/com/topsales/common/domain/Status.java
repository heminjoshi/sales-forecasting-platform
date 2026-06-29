package com.topsales.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Freshness of the returned data — the honest signal the dashboard renders as a badge.
 * Lowercase on the wire. The read path never fails closed: it always returns a body with a
 * status (docs/lld.md §5). In Phase 2 (actuals-only) only {@code fresh} and {@code pending} occur.
 */
public enum Status {
    FRESH,
    STALE,
    PENDING,
    DEGRADED;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Status from(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}
