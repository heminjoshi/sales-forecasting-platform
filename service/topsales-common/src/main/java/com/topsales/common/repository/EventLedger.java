package com.topsales.common.repository;

import com.topsales.common.domain.SaleEvent;

/**
 * The durable event ledger and dedupe gate. {@code record} inserts into events
 * (ON CONFLICT (idempotency_key) DO NOTHING) and appends the raw event to the immutable raw log.
 * Returns {@code true} only when newly inserted; {@code false} means a duplicate (skip the upsert).
 * Built impl: JdbcEventLedger + filesystem raw log; designed: S3. docs/lld.md §4, §6.
 */
public interface EventLedger {
    boolean record(SaleEvent event);
}
