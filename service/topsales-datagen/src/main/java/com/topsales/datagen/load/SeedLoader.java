package com.topsales.datagen.load;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.gen.SeasonalityModel;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Historic backfill ({@code make seed}): clears the tenant's rollup, then bulk-overwrites months of
 * pre-summed, seasonal, channel-split {@link AggregateRow}s straight into the aggregates table. This
 * is a <em>trusted</em> backfill that deliberately bypasses the consumer's idempotency path so the
 * forecaster has months of history to fit. Re-runnable: it converges to an identical state.
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
        ZoneId zone = tenantZone();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(config.historyDays() - 1L);

        SeasonalityModel model = new SeasonalityModel(config, start, today);
        List<AggregateRow> rows = model.generateAggregates();

        int cleared = jdbc.update("DELETE FROM aggregates WHERE tenant_id = ?", config.tenant());
        aggregates.bulkUpsert(rows);

        log.info(
                "seed: tenant={} window={}..{} ({} days) cleared={} rows wrote={} aggregate rows",
                config.tenant(),
                start,
                today,
                config.historyDays(),
                cleared,
                rows.size());
    }

    /** The tenant's IANA zone (bucketing is tenant-local), from the Flyway-seeded tenant_config. */
    private ZoneId tenantZone() {
        String tz =
                jdbc.queryForObject(
                        "SELECT timezone FROM tenant_config WHERE tenant_id = ?",
                        String.class,
                        config.tenant());
        return ZoneId.of(tz);
    }
}
