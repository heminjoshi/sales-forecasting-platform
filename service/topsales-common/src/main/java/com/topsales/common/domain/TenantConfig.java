package com.topsales.common.domain;

import java.time.ZoneId;

/**
 * Per-tenant config that drives tenant-local bucketing (A6) and currency. Loaded before ingesting.
 * docs/lld.md §2, §6.
 */
public record TenantConfig(
        String tenantId,
        ZoneId timezone,
        String reportingCurrency) {
}
