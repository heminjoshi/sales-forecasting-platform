package com.topsales.common.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A single sale event — the ingestion input (POST /api/v1/events, §3.1). Money is
 * minor-unit-safe ({@link BigDecimal}); {@code eventTime} is UTC on the wire. The authoritative
 * tenant is derived from auth (the {@code X-Tenant-Id} header locally), never trusted from this
 * body — see docs/lld.md §11; {@code tenantId} here is advisory.
 */
public record SaleEvent(
        String tenantId,
        String orderId,
        String categoryId,
        Channel channel,
        BigDecimal amount,
        String currency,
        EventType eventType,
        Instant eventTime,
        String idempotencyKey) {

    /** The dedupe key, defaulting to {@code orderId:eventType} when omitted (§3.1). */
    public String effectiveIdempotencyKey() {
        return (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : orderId + ":" + eventType;
    }
}
