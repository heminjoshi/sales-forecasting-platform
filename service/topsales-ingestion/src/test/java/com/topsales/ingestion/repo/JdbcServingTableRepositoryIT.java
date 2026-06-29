package com.topsales.ingestion.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies the versioned write/atomic-swap/prune and the active-version read against a real Postgres.
 * {@code versionKeep = 3}, so at most three versions survive per pk.
 */
@Testcontainers
class JdbcServingTableRepositoryIT {

    private static final String PK = "t_demo#7d#forecast";
    private static final Instant AS_OF = Instant.parse("2026-06-28T00:00:00Z");

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    JdbcServingTableRepository repo;

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
        jdbc.update("TRUNCATE serving_rows, serving_active_version");
        repo = new JdbcServingTableRepository(jdbc, 3);
    }

    private ServingRow forecastRow(int rank, String category, String value) {
        return new ServingRow(
                rank,
                category,
                new BigDecimal(value),
                new BigDecimal(value).subtract(BigDecimal.TEN),
                new BigDecimal(value).add(BigDecimal.TEN),
                Confidence.HIGH,
                new BigDecimal("0.1200"));
    }

    private int activeVersion() {
        return jdbc.queryForObject(
                "SELECT active_version FROM serving_active_version WHERE pk = ?", Integer.class, PK);
    }

    private int distinctVersionCount() {
        return jdbc.queryForObject(
                "SELECT count(DISTINCT version) FROM serving_rows WHERE pk = ?", Integer.class, PK);
    }

    @Test
    void firstWrite_returnsV1_andReadActiveReturnsRowsRanked() {
        int version =
                repo.writeVersionAndSwap(
                        PK,
                        List.of(forecastRow(1, "cat_office", "100.00"), forecastRow(2, "cat_home", "80.00")),
                        AS_OF);

        assertThat(version).isEqualTo(1);
        assertThat(activeVersion()).isEqualTo(1);

        Optional<ServingResult> active = repo.readActive(PK);
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo(1);
        assertThat(active.get().asOf()).isEqualTo(AS_OF);
        assertThat(active.get().rows())
                .extracting(ServingRow::rank, ServingRow::categoryId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "cat_office"),
                        org.assertj.core.groups.Tuple.tuple(2, "cat_home"));
    }

    @Test
    void secondWrite_flipsActiveToV2_butV1RemainsForRollback() {
        repo.writeVersionAndSwap(PK, List.of(forecastRow(1, "cat_office", "100.00")), AS_OF);
        int v2 =
                repo.writeVersionAndSwap(PK, List.of(forecastRow(1, "cat_home", "120.00")), AS_OF);

        assertThat(v2).isEqualTo(2);
        assertThat(activeVersion()).isEqualTo(2);

        // Both versions are still on disk (rollback headroom).
        assertThat(distinctVersionCount()).isEqualTo(2);

        // The active read returns the v2 rows.
        Optional<ServingResult> active = repo.readActive(PK);
        assertThat(active).isPresent();
        assertThat(active.get().version()).isEqualTo(2);
        assertThat(active.get().rows())
                .singleElement()
                .satisfies(r -> assertThat(r.categoryId()).isEqualTo("cat_home"));
    }

    @Test
    void pruneKicksIn_onlyLastVersionKeepVersionsSurvive() {
        for (int i = 1; i <= 5; i++) {
            repo.writeVersionAndSwap(
                    PK, List.of(forecastRow(1, "cat_office", "10" + i + ".00")), AS_OF);
        }

        // versionKeep = 3 → after writing v5, prune deletes version <= 5 - 3 = 2, leaving 3, 4, 5.
        assertThat(activeVersion()).isEqualTo(5);
        assertThat(distinctVersionCount()).isEqualTo(3);

        List<Integer> remaining =
                jdbc.queryForList(
                        "SELECT DISTINCT version FROM serving_rows WHERE pk = ? ORDER BY version",
                        Integer.class,
                        PK);
        assertThat(remaining).containsExactly(3, 4, 5);
    }

    @Test
    void nullIntervalAndConfidence_roundTrips() {
        // An actuals-style row: no interval, no confidence, no delta.
        ServingRow actualsRow =
                new ServingRow(1, "cat_office", new BigDecimal("42.00"), null, null, null, null);
        repo.writeVersionAndSwap(PK, List.of(actualsRow), AS_OF);

        Optional<ServingResult> active = repo.readActive(PK);
        assertThat(active).isPresent();
        assertThat(active.get().rows())
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.value()).isEqualByComparingTo("42.00");
                            assertThat(r.intervalLow()).isNull();
                            assertThat(r.intervalHigh()).isNull();
                            assertThat(r.confidence()).isNull();
                            assertThat(r.deltaVsPrior()).isNull();
                        });
    }

    @Test
    void readActive_emptyWhenPkHasNoActiveVersion() {
        assertThat(repo.readActive("t_demo#unknown#forecast")).isEmpty();
    }
}
