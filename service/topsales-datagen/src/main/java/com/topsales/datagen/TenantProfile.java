package com.topsales.datagen;

import java.time.ZoneId;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A tenant's IANA zone + reporting currency, read from the Flyway-seeded {@code tenant_config} (the
 * source of truth). Bucketing is tenant-local, so the generator must use the same zone the ingestion
 * path would.
 */
public record TenantProfile(ZoneId zone, String currency) {

    public static TenantProfile load(JdbcTemplate jdbc, String tenantId) {
        return jdbc.queryForObject(
                "SELECT timezone, reporting_currency FROM tenant_config WHERE tenant_id = ?",
                (rs, n) -> new TenantProfile(ZoneId.of(rs.getString(1)), rs.getString(2)),
                tenantId);
    }
}
