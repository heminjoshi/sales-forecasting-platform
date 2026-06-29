package com.topsales.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Read mode. Lowercase on the wire (LLD conventions). {@code actuals} reads straight from the
 * aggregates; {@code forecast} reads precomputed serving rows with the degradation chain (Phase 4).
 */
public enum Mode {
    FORECAST,
    ACTUALS;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Mode from(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}
