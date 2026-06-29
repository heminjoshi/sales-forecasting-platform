package com.topsales.ingestion.repo;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local {@link QuarantineRepository} impl over the {@code quarantine} dead-letter table.
 * docs/lld.md §3.1, §6. Profiled {@code local}; {@code aws} routes to an SQS DLQ.
 */
@Repository
@Profile("local")
public class JdbcQuarantineRepository implements QuarantineRepository {

    private static final String INSERT =
            "INSERT INTO quarantine (tenant_id, payload, reason) VALUES (?, ?, ?)";

    private final JdbcTemplate jdbc;

    public JdbcQuarantineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void quarantine(String tenantId, String payload, String reason) {
        jdbc.update(INSERT, tenantId, payload, reason);
    }
}
