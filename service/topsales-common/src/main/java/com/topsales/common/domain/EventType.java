package com.topsales.common.domain;

/**
 * Kind of sale event. Uppercase on the wire (matches the DDL CHECK constraint and the §3.1
 * request example) — Jackson serializes via {@code name()} by default, so no annotations needed.
 * RETURN/ADJUSTMENT carry signed (negative) amounts so they net correctly in the aggregate.
 */
public enum EventType {
    SALE,
    RETURN,
    ADJUSTMENT
}
