package com.topsales.common.domain;

/**
 * Sales channel — a first-class key dimension (ADR-0010). Uppercase on the wire (like
 * {@link EventType}); Jackson serializes via {@code name()} by default, so no annotations needed. A
 * persisted event or aggregate row always carries a concrete channel; the read-time {@code all}
 * rollup is modelled separately by {@link ChannelFilter}, never stored as a channel value.
 */
public enum Channel {
    ONLINE,
    OFFLINE
}
