package com.topsales.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the authoritative rollup: a tenant's category total for a single channel on a single
 * tenant-local day (the aggregates table PK after ADR-0010). docs/lld.md §2.
 */
public record AggregateRow(
        String tenantId,
        String categoryId,
        Channel channel,
        LocalDate bucketDate,
        BigDecimal sumAmount,
        int orderCount,
        String currency) {
}
