package com.topsales.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.api.TopKItem;
import com.topsales.common.api.TopKResponse;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end read path against a real Postgres: boot the full app, POST a few events through the
 * ingestion controller, then GET the ranked top-k and assert the order. Flyway auto-migrates from
 * the classpath (which also seeds the {@code t_demo} tenant via V4).
 *
 * <p>NOTE: Testcontainers may fail to start on some hosts (Docker API vs the bundled docker-java
 * client). When it does, this IT is skipped locally but is correct for CI.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TopCategoriesReadIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @LocalServerPort int port;
    @Autowired ObjectMapper objectMapper;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private String event(String orderId, String category, String amount) {
        // eventTime is relative to now so the events always land inside the trailing month window.
        String eventTime = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        return String.format(
                "{\"tenantId\":\"t_demo\",\"orderId\":\"%s\",\"categoryId\":\"%s\",\"amount\":%s,"
                        + "\"currency\":\"USD\",\"eventType\":\"SALE\",\"eventTime\":\"%s\"}",
                orderId, category, amount, eventTime);
    }

    @Test
    void postEvents_thenReadTopCategories_ranksByActualSales() throws Exception {
        String body =
                "["
                        + event("o1", "cat_a", "100.00") + ","
                        + event("o2", "cat_a", "200.00") + ","
                        + event("o3", "cat_b", "150.00") + ","
                        + event("o4", "cat_c", "50.00")
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

        ResponseEntity<String> read =
                client()
                        .get()
                        .uri("/api/v1/tenants/t_demo/top-categories?mode=actuals&window=month&k=10")
                        .header("X-Tenant-Id", "t_demo")
                        .retrieve()
                        .toEntity(String.class);

        assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
        TopKResponse response = objectMapper.readValue(read.getBody(), TopKResponse.class);

        assertThat(response.items()).extracting(TopKItem::category)
                .containsExactly("cat_a", "cat_b", "cat_c");
        assertThat(response.items().get(0).value()).isEqualByComparingTo("300.00");
        assertThat(response.status().name()).isEqualTo("FRESH");
    }
}
