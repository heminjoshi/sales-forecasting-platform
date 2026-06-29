package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.api.it.AbstractPostgresRedisIT;
import com.topsales.common.api.TopKResponse;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.Status;
import com.topsales.common.domain.Window;
import com.topsales.common.forecast.ServingKey;
import com.topsales.common.forecast.ServingRow;
import com.topsales.common.forecast.ServingTableRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end coverage of the forecast read path's degradation ladder (docs/lld.md §5) against real
 * Postgres + Redis: a read must never fail closed. Both tests boot the full app via
 * {@link AbstractPostgresRedisIT} and drive the public {@code /api/v1/...} surface only.
 *
 * <p>The two cases deliberately use <em>disjoint</em> query keys — {@code window=month} for the
 * empty-serving (degraded/pending) case and {@code window=week} for the fresh case — so they cannot
 * collide on the per-tenant Redis cache key {@code topk:t_demo:{ver}:{window}:forecast:all:{k}} or on
 * the serving partition key, keeping the two {@code @Test}s order-independent in the shared context.
 */
class ForecastDegradationIT extends AbstractPostgresRedisIT {

    @Autowired ServingTableRepository servingTable;

    /** A single SALE event for {@code t_demo}, dated inside the trailing-month window. */
    private String event(String orderId, String category, String amount) {
        String eventTime = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        return String.format(
                "{\"tenantId\":\"t_demo\",\"orderId\":\"%s\",\"categoryId\":\"%s\","
                        + "\"channel\":\"ONLINE\",\"amount\":%s,"
                        + "\"currency\":\"USD\",\"eventType\":\"SALE\",\"eventTime\":\"%s\"}",
                orderId, category, amount, eventTime);
    }

    private TopKResponse forecast(String query) throws Exception {
        ResponseEntity<String> read =
                client()
                        .get()
                        .uri("/api/v1/tenants/t_demo/top-categories?" + query)
                        .header("X-Tenant-Id", "t_demo")
                        .retrieve()
                        .toEntity(String.class);
        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(read.getBody(), TopKResponse.class);
    }

    /**
     * IT-FC-07: with the serving table empty for this key but actuals present, a forecast read still
     * returns HTTP 200 — never a 5xx. The ladder yields the seasonal-naive {@code degraded} rung when
     * actuals exist (or {@code pending} as the always-available floor); either is honest, so we accept
     * both. The point: the read path survives a total forecast-plane absence.
     */
    @Test
    void noServingRows_stillReturns200Degraded() throws Exception {
        // Some actuals for t_demo so the degraded (seasonal-naive) rung has data to recompute from.
        String body =
                "["
                        + event("fc07-a", "cat_a", "100.00") + ","
                        + event("fc07-b", "cat_a", "200.00") + ","
                        + event("fc07-c", "cat_b", "150.00")
                        + "]";
        ResponseEntity<String> ingest =
                client()
                        .post()
                        .uri("/api/v1/events")
                        .header("X-Tenant-Id", "t_demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toEntity(String.class);
        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // No serving rows were ever written for t_demo#month#forecast#all → rungs 1–2 miss.
        TopKResponse response = forecast("mode=forecast&window=month&k=10");

        assertThat(response.status()).isIn(Status.DEGRADED, Status.PENDING);
        // Either rung still renders the ranked actuals it derived from (never an error payload).
        assertThat(response.items()).isNotEmpty();
    }

    /**
     * IT-FC-02: after the batch writes active serving rows with a recent {@code asOf}, a forecast read
     * resolves on rung 1 — {@code fresh} — and the items carry the forecast-only confidence + interval
     * the dashboard renders. We seed the serving table directly (as the batch would) under the exact
     * {@link ServingKey} the {@code PrecomputedForecastProvider} reads, then read the public endpoint.
     */
    @Test
    void forecast_afterBatch_isFreshWithIntervals() throws Exception {
        // The batch writes versioned rows + flips the active pointer; asOf=now is well inside the 36h
        // freshness SLO, so the ladder labels rung 1 'fresh' (not 'stale').
        String pk = ServingKey.of("t_demo", Window.WEEK, Mode.FORECAST, ChannelFilter.ALL);
        List<ServingRow> rows =
                List.of(
                        forecastRow(1, "cat_office", "120.00"),
                        forecastRow(2, "cat_home", "90.00"),
                        forecastRow(3, "cat_garden", "60.00"));
        servingTable.writeVersionAndSwap(pk, rows, Instant.now());

        TopKResponse response = forecast("mode=forecast&window=week&k=5");

        assertThat(response.status()).isEqualTo(Status.FRESH);
        assertThat(response.items()).isNotEmpty();
        // Forecast rows carry confidence + a [low, high] interval (actuals/pending omit both).
        assertThat(response.items().get(0).confidence()).isNotNull();
        assertThat(response.items().get(0).interval()).isNotNull();
    }

    /** A forecast-style serving row: HIGH confidence, a ±10 interval, and a +12% delta. */
    private ServingRow forecastRow(int rank, String category, String value) {
        BigDecimal v = new BigDecimal(value);
        return new ServingRow(
                rank,
                category,
                v,
                v.subtract(BigDecimal.TEN),
                v.add(BigDecimal.TEN),
                Confidence.HIGH,
                new BigDecimal("0.1200"));
    }
}
