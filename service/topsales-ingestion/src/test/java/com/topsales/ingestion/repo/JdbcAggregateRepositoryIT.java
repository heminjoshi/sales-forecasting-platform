package com.topsales.ingestion.repo;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.ChannelFilter;

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

    private AggregateDelta delta(String category, Channel channel, LocalDate day, String amount) {
        return new AggregateDelta("tenant_a", category, channel, day, new BigDecimal(amount), "USD");
    }

    @Test
    void twoUpsertsSameKey_addAmounts_andIncrementCount() {
        LocalDate day = LocalDate.of(2026, 6, 20);
        repo.upsertAdditive(delta("cat_office", Channel.ONLINE, day, "10.00"));
        repo.upsertAdditive(delta("cat_office", Channel.ONLINE, day, "5.50"));

        BigDecimal sum =
                jdbc.queryForObject(
                        "SELECT sum_amount FROM aggregates WHERE tenant_id=? AND category_id=? AND bucket_date=?",
                        BigDecimal.class,
                        "tenant_a",
                        "cat_office",
                        java.sql.Date.valueOf(day));
        Integer orderCount =
                jdbc.queryForObject(
                        "SELECT order_count FROM aggregates WHERE tenant_id=? AND category_id=? AND bucket_date=?",
                        Integer.class,
                        "tenant_a",
                        "cat_office",
                        java.sql.Date.valueOf(day));

        assertThat(sum).isEqualByComparingTo("15.50");
        assertThat(orderCount).isEqualTo(2);
    }

    @Test
    void rangeByCategory_returnsRowsInRange() {
        LocalDate d19 = LocalDate.of(2026, 6, 19);
        LocalDate d20 = LocalDate.of(2026, 6, 20);
        repo.upsertAdditive(delta("cat_office", Channel.ONLINE, d19, "10.00"));
        repo.upsertAdditive(delta("cat_home", Channel.ONLINE, d20, "20.00"));
        repo.upsertAdditive(
                delta("cat_office", Channel.ONLINE, LocalDate.of(2026, 7, 1), "99.00")); // out of range

        List<AggregateRow> rows = repo.rangeByCategory("tenant_a", d19, d20, ChannelFilter.ALL);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(AggregateRow::categoryId, AggregateRow::bucketDate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("cat_home", d20),
                        org.assertj.core.groups.Tuple.tuple("cat_office", d19));
    }

    @Test
    void channelIsPartOfTheKey_rowsStaySeparate_andRangeFilters() {
        LocalDate day = LocalDate.of(2026, 6, 20);
        repo.upsertAdditive(delta("cat_office", Channel.ONLINE, day, "10.00"));
        repo.upsertAdditive(delta("cat_office", Channel.OFFLINE, day, "7.00"));

        // Same (tenant, category, day) but different channels → two distinct rows.
        List<AggregateRow> all = repo.rangeByCategory("tenant_a", day, day, ChannelFilter.ALL);
        assertThat(all)
                .extracting(AggregateRow::channel, AggregateRow::sumAmount)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(Channel.ONLINE, new BigDecimal("10.00")),
                        org.assertj.core.groups.Tuple.tuple(Channel.OFFLINE, new BigDecimal("7.00")));

        // The filter narrows to one channel.
        List<AggregateRow> online = repo.rangeByCategory("tenant_a", day, day, ChannelFilter.ONLINE);
        assertThat(online).singleElement().satisfies(r -> {
            assertThat(r.channel()).isEqualTo(Channel.ONLINE);
            assertThat(r.sumAmount()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void bulkUpsert_overwrites_andIsReRunnableToIdenticalState() {
        LocalDate day = LocalDate.of(2026, 6, 20);
        AggregateRow row =
                new AggregateRow(
                        "tenant_a", "cat_office", Channel.ONLINE, day, new BigDecimal("100.00"), 5, "USD");

        repo.bulkUpsert(List.of(row));
        repo.bulkUpsert(List.of(row)); // re-run: SET (not +=), so it converges, not accumulates

        List<AggregateRow> rows = repo.rangeByCategory("tenant_a", day, day, ChannelFilter.ALL);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.sumAmount()).isEqualByComparingTo("100.00");
            assertThat(r.orderCount()).isEqualTo(5);
        });
    }
}
