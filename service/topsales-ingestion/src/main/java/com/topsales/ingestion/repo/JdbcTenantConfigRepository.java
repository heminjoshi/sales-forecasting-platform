package com.topsales.ingestion.repo;

import com.topsales.common.domain.TenantConfig;
import com.topsales.common.repository.TenantConfigRepository;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Local {@link TenantConfigRepository} impl over {@code tenant_config}. An empty result means an
 * unknown tenant — ingestion quarantines, the read path maps it to 404. docs/lld.md §4, §6, §11.
 */
@Repository
@Profile("local")
public class JdbcTenantConfigRepository implements TenantConfigRepository {

    private static final String FIND =
            "SELECT timezone, reporting_currency FROM tenant_config WHERE tenant_id = ?";

    private static final String ALL_IDS =
            "SELECT tenant_id FROM tenant_config ORDER BY tenant_id";

    private final JdbcTemplate jdbc;

    public JdbcTenantConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantConfig> find(String tenantId) {
        RowMapper<TenantConfig> mapper =
                (rs, n) ->
                        new TenantConfig(
                                tenantId,
                                ZoneId.of(rs.getString("timezone")),
                                rs.getString("reporting_currency"));
        List<TenantConfig> rows = jdbc.query(FIND, mapper, tenantId);
        return rows.stream().findFirst();
    }

    @Override
    public List<String> allTenantIds() {
        return jdbc.queryForList(ALL_IDS, String.class);
    }
}
