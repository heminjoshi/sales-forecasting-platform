package com.topsales.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One event's additive contribution to the rollup, applied via
 * {@code AggregateRepository.upsertAdditive} (ON CONFLICT DO UPDATE +=). The order count always
 * increments by one; {@code amount} is signed. docs/lld.md §6.
 */
public record AggregateDelta(
        String tenantId,
        String categoryId,
        LocalDate bucketDate,
        BigDecimal amount,
        String currency) {
}
