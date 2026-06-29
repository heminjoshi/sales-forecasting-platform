package com.topsales.ingestion.repo;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.repository.AggregateRepository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Local {@link AggregateRepository} impl over the Postgres {@code aggregates} rollup. Writes are an
 * additive upsert (independent of arrival order); reads are a keyed range scan for windowed ranking.
 * docs/lld.md §4, §6. Profiled {@code local}; {@code aws} swaps in Aurora behind the same port.
 */
@Repository
@Profile("local")
public class JdbcAggregateRepository implements AggregateRepository {

    private static final String UPSERT_ADDITIVE =
            """
            INSERT INTO aggregates
                (tenant_id, category_id, bucket_date, sum_amount, order_count, currency)
            VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT (tenant_id, category_id, bucket_date)
            DO UPDATE SET sum_amount  = aggregates.sum_amount + EXCLUDED.sum_amount,
                          order_count = aggregates.order_count + 1,
                          updated_at  = now()
            """;

    private static final String RANGE_BY_CATEGORY =
            """
            SELECT tenant_id, category_id, bucket_date, sum_amount, order_count, currency
            FROM aggregates
            WHERE tenant_id = ? AND bucket_date BETWEEN ? AND ?
            ORDER BY category_id, bucket_date
            """;

    private static final RowMapper<AggregateRow> ROW_MAPPER =
            (rs, n) ->
                    new AggregateRow(
                            rs.getString("tenant_id"),
                            rs.getString("category_id"),
                            rs.getDate("bucket_date").toLocalDate(),
                            rs.getBigDecimal("sum_amount"),
                            rs.getInt("order_count"),
                            rs.getString("currency"));

    private final JdbcTemplate jdbc;

    public JdbcAggregateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int upsertAdditive(AggregateDelta delta) {
        return jdbc.update(
                UPSERT_ADDITIVE,
                delta.tenantId(),
                delta.categoryId(),
                Date.valueOf(delta.bucketDate()),
                delta.amount(),
                delta.currency());
    }

    @Override
    public List<AggregateRow> rangeByCategory(String tenantId, LocalDate from, LocalDate to) {
        return jdbc.query(
                RANGE_BY_CATEGORY, ROW_MAPPER, tenantId, Date.valueOf(from), Date.valueOf(to));
    }
}
