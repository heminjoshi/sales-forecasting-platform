package com.topsales.datagen.load;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.TenantProfile;
import com.topsales.datagen.gen.SeasonalityModel;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Historic backfill ({@code make seed}): for each configured tenant, clears its rollup then
 * bulk-overwrites months of pre-summed, seasonal, channel-split {@link AggregateRow}s straight into
 * the aggregates table. A <em>trusted</em> backfill that deliberately bypasses the consumer's
 * idempotency path so the forecaster has months of history to fit. Re-runnable: it converges to an
 * identical state. Each tenant's data is keyed independently (multi-tenant isolation).
 */
@Component
public class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private final SeedConfig config;
    private final AggregateRepository aggregates;
    private final JdbcTemplate jdbc;

    public SeedLoader(SeedConfig config, AggregateRepository aggregates, JdbcTemplate jdbc) {
        this.config = config;
        this.aggregates = aggregates;
        this.jdbc = jdbc;
    }

    public void run() {
        for (String tenantId : config.tenants()) {
            TenantProfile profile = TenantProfile.load(jdbc, tenantId);
            LocalDate today = LocalDate.now(profile.zone());
            LocalDate start = today.minusDays(config.historyDays() - 1L);

            SeasonalityModel model =
                    new SeasonalityModel(config, tenantId, profile.currency(), start, today);
            List<AggregateRow> rows = model.generateAggregates();

            int cleared = jdbc.update("DELETE FROM aggregates WHERE tenant_id = ?", tenantId);
            aggregates.bulkUpsert(rows);

            log.info(
                    "seed: tenant={} window={}..{} ({} days) cleared={} wrote={} aggregate rows",
                    tenantId,
                    start,
                    today,
                    config.historyDays(),
                    cleared,
                    rows.size());
        }
    }
}
