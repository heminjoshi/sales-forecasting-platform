package com.topsales.ingestion.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the additive upsert and range scan against a real Postgres. */
@Testcontainers
class JdbcAggregateRepositoryIT {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    static JdbcTemplate jdbc;
    JdbcAggregateRepository repo;

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
        jdbc.update("TRUNCATE aggregates");
        repo = new JdbcAggregateRepository(jdbc);
    }

    private AggregateDelta delta(String category, LocalDate day, String amount) {
        return new AggregateDelta("t_demo", category, day, new BigDecimal(amount), "USD");
    }

    @Test
    void twoUpsertsSameKey_addAmounts_andIncrementCount() {
        LocalDate day = LocalDate.of(2026, 6, 20);
        repo.upsertAdditive(delta("cat_office", day, "10.00"));
        repo.upsertAdditive(delta("cat_office", day, "5.50"));

        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT sum_amount FROM aggregates WHERE tenant_id=? AND category_id=? AND bucket_date=?",
                        BigDecimal.class,
                        "t_demo",
                        "cat_office",
                        java.sql.Date.valueOf(day));
        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT order_count FROM aggregates WHERE tenant_id=? AND category_id=? AND bucket_date=?",
                        Integer.class,
                        "t_demo",
                        "cat_office",
                        java.sql.Date.valueOf(day));

        assertThat(sum).isEqualByComparingTo("15.50");
        assertThat(orderCount).isEqualTo(2);
    }

    @Test
    void rangeByCategory_returnsRowsInRange() {
        LocalDate d19 = LocalDate.of(2026, 6, 19);
        LocalDate d20 = LocalDate.of(2026, 6, 20);
        repo.upsertAdditive(delta("cat_office", d19, "10.00"));
        repo.upsertAdditive(delta("cat_home", d20, "20.00"));
        repo.upsertAdditive(delta("cat_office", LocalDate.of(2026, 7, 1), "99.00")); // out of range

        List<AggregateRow> rows =
                repo.rangeByCategory("t_demo", d19, d20);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(AggregateRow::categoryId, AggregateRow::bucketDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("cat_home", d20),
                        org.assertj.core.groups.Tuple.tuple("cat_office", d19));
    }
}
