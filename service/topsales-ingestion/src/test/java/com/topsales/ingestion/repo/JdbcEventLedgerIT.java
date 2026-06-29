package com.topsales.ingestion.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.EventType;
import com.topsales.common.domain.SaleEvent;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Verifies the dedupe gate and raw-log append against a real Postgres. */
@Testcontainers
class JdbcEventLedgerIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;

    @TempDir static Path rawLogDir;

    JdbcEventLedger ledger;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        DriverManagerDataSource ds =
                new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        jdbc = new JdbcTemplate(ds);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("TRUNCATE events");
        ObjectMapper mapper = JsonMapper.builder().build();
        TopsalesProperties props =
                new TopsalesProperties(
                        null, null, null, null, null, new TopsalesProperties.Rawlog(rawLogDir.toString()), null);
        ledger = new JdbcEventLedger(jdbc, mapper, props);
    }

    private SaleEvent sale() {
        return new SaleEvent(
                "t_demo",
                "o_1",
                "cat_office",
                Channel.ONLINE,
                new BigDecimal("42.50"),
                "USD",
                EventType.SALE,
                Instant.parse("2026-06-20T14:03:00Z"),
                "o_1:SALE");
    }

    @Test
    void firstRecord_insertsRow_andAppendsRawLog() throws Exception {
        boolean inserted = ledger.record(sale(), LocalDate.of(2026, 6, 20));

        assertThat(inserted).isTrue();
        Integer count = jdbc.queryForObject("SELECT count(*) FROM events", Integer.class);
        assertThat(count).isEqualTo(1);

        Path rawLog = rawLogDir.resolve("events.ndjson");
        assertThat(Files.exists(rawLog)).isTrue();
        assertThat(Files.readAllLines(rawLog)).isNotEmpty();
    }

    @Test
    void duplicateIdempotencyKey_returnsFalse_andDoesNotDoubleInsert() {
        LocalDate bucket = LocalDate.of(2026, 6, 20);

        assertThat(ledger.record(sale(), bucket)).isTrue();
        assertThat(ledger.record(sale(), bucket)).isFalse(); // same idempotency_key

        Integer count = jdbc.queryForObject("SELECT count(*) FROM events", Integer.class);
        assertThat(count).isEqualTo(1);
    }
}
