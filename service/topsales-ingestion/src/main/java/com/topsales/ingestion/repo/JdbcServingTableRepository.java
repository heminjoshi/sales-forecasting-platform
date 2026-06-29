package com.topsales.ingestion.repo;

import com.topsales.common.domain.Confidence;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingRow;
import com.topsales.common.forecast.ServingTableRepository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Local {@link ServingTableRepository} impl over the Postgres serving tables. The write is a
 * versioned, copy-on-write swap (DR-1): new rows land at {@code max(version)+1}, then the active
 * pointer is flipped in one statement so the last-good version stays readable until the instant of
 * the swap. Reads join {@code serving_active_version} to the rows of the active version.
 * docs/lld.md §3, §8. Profiled {@code local}; {@code aws} swaps in DynamoDB behind the same port.
 *
 * <p>The whole write runs in one {@link TransactionTemplate} (insert → swap → prune are
 * all-or-nothing). Like {@code IngestionService}, this drives the transaction explicitly rather than
 * via {@code @Transactional} so atomicity holds even when the repository is constructed directly
 * (e.g. in the integration test) without a Spring proxy.
 */
@Repository
@Profile("local")
public class JdbcServingTableRepository implements ServingTableRepository {

    /** Next version for a pk: {@code max+1}, not {@code active+1}, so a rerun after a rollback never collides. */
    private static final String NEXT_VERSION =
            "SELECT COALESCE(max(version), 0) + 1 FROM serving_rows WHERE pk = ?";

    private static final String INSERT_ROW =
            """
            INSERT INTO serving_rows
                (pk, version, rank, category_id, value,
                 interval_low, interval_high, confidence, delta_vs_prior, as_of)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** The atomic swap: point pk at the just-written version (upsert, so first write also inserts). */
    private static final String SWAP_ACTIVE =
            """
            INSERT INTO serving_active_version (pk, active_version, as_of)
            VALUES (?, ?, ?)
            ON CONFLICT (pk)
            DO UPDATE SET active_version = EXCLUDED.active_version,
                          as_of         = EXCLUDED.as_of
            """;

    /** Keep only the most recent {@code versionKeep} versions; drop everything older. */
    private static final String PRUNE_OLD =
            "DELETE FROM serving_rows WHERE pk = ? AND version <= ?";

    private static final String SELECT_ACTIVE =
            """
            SELECT av.active_version, av.as_of,
                   sr.rank, sr.category_id, sr.value,
                   sr.interval_low, sr.interval_high, sr.confidence, sr.delta_vs_prior
            FROM serving_active_version av
            JOIN serving_rows sr ON sr.pk = av.pk AND sr.version = av.active_version
            WHERE av.pk = ?
            ORDER BY sr.rank
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final int versionKeep;

    /**
     * @param jdbc        template over the local Postgres datasource
     * @param versionKeep how many recent versions to retain for rollback headroom (older ones are pruned)
     */
    public JdbcServingTableRepository(JdbcTemplate jdbc, int versionKeep) {
        this.jdbc = jdbc;
        this.versionKeep = versionKeep;
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    @Override
    public int writeVersionAndSwap(String pk, List<ServingRow> rows, Instant asOf) {
        Timestamp ts = Timestamp.from(asOf);
        return tx.execute(status -> {
            int newVersion = jdbc.queryForObject(NEXT_VERSION, Integer.class, pk);

            jdbc.batchUpdate(
                    INSERT_ROW,
                    new BatchPreparedStatementSetter() {
                        @Override
                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                            ServingRow r = rows.get(i);
                            ps.setString(1, pk);
                            ps.setInt(2, newVersion);
                            ps.setInt(3, r.rank());
                            ps.setString(4, r.categoryId());
                            ps.setBigDecimal(5, r.value());
                            ps.setBigDecimal(6, r.intervalLow());
                            ps.setBigDecimal(7, r.intervalHigh());
                            ps.setString(8, r.confidence() == null ? null : r.confidence().name());
                            ps.setBigDecimal(9, r.deltaVsPrior());
                            ps.setTimestamp(10, ts);
                        }

                        @Override
                        public int getBatchSize() {
                            return rows.size();
                        }
                    });

            jdbc.update(SWAP_ACTIVE, pk, newVersion, ts);

            // Retain [newVersion - versionKeep + 1 .. newVersion]; delete strictly older.
            jdbc.update(PRUNE_OLD, pk, newVersion - versionKeep);

            return newVersion;
        });
    }

    @Override
    public Optional<ServingResult> readActive(String pk) {
        return jdbc.query(
                SELECT_ACTIVE,
                rs -> {
                    List<ServingRow> rows = new ArrayList<>();
                    int version = 0;
                    Instant asOf = null;
                    while (rs.next()) {
                        version = rs.getInt("active_version");
                        asOf = rs.getObject("as_of", OffsetDateTime.class).toInstant();
                        String confidence = rs.getString("confidence");
                        rows.add(
                                new ServingRow(
                                        rs.getInt("rank"),
                                        rs.getString("category_id"),
                                        rs.getBigDecimal("value"),
                                        rs.getBigDecimal("interval_low"),
                                        rs.getBigDecimal("interval_high"),
                                        confidence == null ? null : Confidence.valueOf(confidence),
                                        rs.getBigDecimal("delta_vs_prior")));
                    }
                    return rows.isEmpty()
                            ? Optional.<ServingResult>empty()
                            : Optional.of(new ServingResult(rows, version, asOf));
                },
                pk);
    }
}
