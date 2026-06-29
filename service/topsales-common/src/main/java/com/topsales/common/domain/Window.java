package com.topsales.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Aggregation window. Lowercase on the wire (LLD conventions). {@code quarter} is reserved
 * (not yet implemented).
 */
public enum Window {
    WEEK,
    MONTH,
    YEAR;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static Window from(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}
