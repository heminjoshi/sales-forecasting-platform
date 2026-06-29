package com.topsales.common.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the authoritative rollup: a tenant's category total for a single tenant-local day
 * (the aggregates table PK). docs/lld.md §2.
 */
public record AggregateRow(
        String tenantId,
        String categoryId,
        LocalDate bucketDate,
        BigDecimal sumAmount,
        int orderCount,
        String currency) {
}
