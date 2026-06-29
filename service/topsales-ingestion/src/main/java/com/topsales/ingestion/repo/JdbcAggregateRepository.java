package com.topsales.ingestion.repo;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.repository.AggregateRepository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Local {@link AggregateRepository} impl over the Postgres {@code aggregates} rollup. Writes are an
 * additive upsert (independent of arrival order); reads are a keyed range scan for windowed ranking.
 * The key includes {@code channel} (ADR-0010). docs/lld.md §4, §6. Profiled {@code local};
 * {@code aws} swaps in Aurora behind the same port.
 */
@Repository
@Profile("local")
public class JdbcAggregateRepository implements AggregateRepository {

    private static final String UPSERT_ADDITIVE =
            """
            INSERT INTO aggregates
                (tenant_id, category_id, channel, bucket_date, sum_amount, order_count, currency)
            VALUES (?, ?, ?, ?, ?, 1, ?)
            ON CONFLICT (tenant_id, category_id, channel, bucket_date)
            DO UPDATE SET sum_amount  = aggregates.sum_amount + EXCLUDED.sum_amount,
                          order_count = aggregates.order_count + 1,
                          updated_at  = now()
            """;

    /** Overwrite-upsert of pre-summed rows (trusted seed backfill): SET, not +=, so it is re-runnable. */
    private static final String BULK_UPSERT =
            """
            INSERT INTO aggregates
                (tenant_id, category_id, channel, bucket_date, sum_amount, order_count, currency)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, category_id, channel, bucket_date)
            DO UPDATE SET sum_amount  = EXCLUDED.sum_amount,
                          order_count = EXCLUDED.order_count,
                          updated_at  = now()
            """;

    private static final String RANGE_ALL =
            """
            SELECT tenant_id, category_id, channel, bucket_date, sum_amount, order_count, currency
            FROM aggregates
            WHERE tenant_id = ? AND bucket_date BETWEEN ? AND ?
            ORDER BY category_id, channel, bucket_date
            """;

    private static final String RANGE_ONE_CHANNEL =
            """
            SELECT tenant_id, category_id, channel, bucket_date, sum_amount, order_count, currency
            FROM aggregates
            WHERE tenant_id = ? AND bucket_date BETWEEN ? AND ? AND channel = ?
            ORDER BY category_id, channel, bucket_date
            """;

    private static final RowMapper<AggregateRow> ROW_MAPPER =
            (rs, n) ->
                    new AggregateRow(
                            rs.getString("tenant_id"),
                            rs.getString("category_id"),
                            Channel.valueOf(rs.getString("channel")),
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
                delta.channel().name(),
                Date.valueOf(delta.bucketDate()),
                delta.amount(),
                delta.currency());
    }

    @Override
    public int[] bulkUpsert(List<AggregateRow> rows) {
        return jdbc.batchUpdate(
                BULK_UPSERT,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        AggregateRow r = rows.get(i);
                        ps.setString(1, r.tenantId());
                        ps.setString(2, r.categoryId());
                        ps.setString(3, r.channel().name());
                        ps.setDate(4, Date.valueOf(r.bucketDate()));
                        ps.setBigDecimal(5, r.sumAmount());
                        ps.setInt(6, r.orderCount());
                        ps.setString(7, r.currency());
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });
    }

    @Override
    public List<AggregateRow> rangeByCategory(
            String tenantId, LocalDate from, LocalDate to, ChannelFilter channel) {
        if (channel == ChannelFilter.ALL) {
            return jdbc.query(
                    RANGE_ALL, ROW_MAPPER, tenantId, Date.valueOf(from), Date.valueOf(to));
        }
        return jdbc.query(
                RANGE_ONE_CHANNEL,
                ROW_MAPPER,
                tenantId,
                Date.valueOf(from),
                Date.valueOf(to),
                channel.name());
    }
}
