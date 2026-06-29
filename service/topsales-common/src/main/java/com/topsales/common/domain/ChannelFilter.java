package com.topsales.common.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The read-side channel selector (API query param {@code channel}). Lowercase on the wire and
 * parsed case-insensitively (like {@link Window}/{@link Mode}); default {@code all}. {@code ALL} is
 * the summed rollup across every {@link Channel} — for actuals it is computed at read time, so it is
 * never persisted as a stored channel value. ADR-0010.
 */
public enum ChannelFilter {
    ALL,
    ONLINE,
    OFFLINE;

    @JsonValue
    public String wire() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static ChannelFilter from(String s) {
        return valueOf(s.trim().toUpperCase());
    }
}
