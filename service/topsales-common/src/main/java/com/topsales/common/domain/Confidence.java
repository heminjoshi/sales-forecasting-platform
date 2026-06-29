package com.topsales.common.domain;

/**
 * Forecast confidence band. Uppercase on the wire (matches the DDL CHECK and the §13 response
 * example). Absent in actuals/pending responses. Set by the forecast batch (Phase 3).
 */
public enum Confidence {
    HIGH,
    MEDIUM,
    LOW
}
