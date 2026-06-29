package com.topsales.datagen.load;

import com.topsales.common.domain.SaleEvent;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.gen.SeasonalityModel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Realtime trickle ({@code make trickle}): emits individual {@link SaleEvent}s for "today" that
 * continue the seeded history (same model, same day) and POSTs them to the running API's
 * {@code /api/v1/events} — exercising the consumer's dedupe/idempotency/upsert path and powering the
 * "watch the dashboard move" demo. Re-run it to add more.
 */
@Component
public class TrickleRunner {

    private static final Logger log = LoggerFactory.getLogger(TrickleRunner.class);

    private final SeedConfig config;
    private final JdbcTemplate jdbc;
    private final RestClient restClient;

    public TrickleRunner(SeedConfig config, JdbcTemplate jdbc, RestClient restClient) {
        this.config = config;
        this.jdbc = jdbc;
        this.restClient = restClient;
    }

    public void run() {
        ZoneId zone = tenantZone();
        LocalDate today = LocalDate.now(zone);
        LocalDate start = today.minusDays(config.historyDays() - 1L);
        Instant now = Instant.now();
        String nonce = Long.toString(now.toEpochMilli(), 36);

        SeasonalityModel model = new SeasonalityModel(config, start, today);
        List<SaleEvent> events = model.generateEventsForDay(today, now, nonce);

        String response =
                restClient
                        .post()
                        .uri("/api/v1/events")
                        .header("X-Tenant-Id", config.tenant())
                        .body(events)
                        .retrieve()
                        .body(String.class);

        log.info("trickle: posted {} events for {} → {}", events.size(), today, response);
    }

    private ZoneId tenantZone() {
        String tz =
                jdbc.queryForObject(
                        "SELECT timezone FROM tenant_config WHERE tenant_id = ?",
                        String.class,
                        config.tenant());
        return ZoneId.of(tz);
    }
}
