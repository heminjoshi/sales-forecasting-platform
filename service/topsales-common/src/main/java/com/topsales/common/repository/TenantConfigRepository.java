package com.topsales.common.repository;

import com.topsales.common.domain.TenantConfig;

import java.util.Optional;

/**
 * Port over tenant_config (timezone + reporting currency). Returns empty for an unknown tenant —
 * the read path maps that to 404, ingestion quarantines the event. docs/lld.md §4, §6, §11.
 */
public interface TenantConfigRepository {
    Optional<TenantConfig> find(String tenantId);
}
