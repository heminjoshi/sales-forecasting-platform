package com.topsales.ingestion.repo;

import com.topsales.common.domain.SaleEvent;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local {@link com.topsales.common.repository.EventLedger} impl: a filesystem raw log (the immutable
 * S3 stand-in) plus the Postgres {@code events} table whose unique {@code idempotency_key} is the
 * dedupe gate. docs/lld.md §4, §6.
 *
 * <p>Profiled {@code local}; the {@code aws} profile swaps in an S3/DynamoDB-backed impl behind the
 * same port.
 */
@Repository
@Profile("local")
public class JdbcEventLedger implements BucketingEventLedger {

    private static final String INSERT_EVENT =
            """
            INSERT INTO events
                (tenant_id, order_id, category_id, amount, currency, event_type,
                 event_time, bucket_date, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path rawLogFile;

    public JdbcEventLedger(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${topsales.rawlog.dir}") String rawLogDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.rawLogFile = Path.of(rawLogDir, "events.ndjson");
    }

    /**
     * Convenience for the generic port: derives the bucket from the event's UTC calendar day. The
     * authoritative tenant-local path goes through {@link #record(SaleEvent, LocalDate)} with a
     * tz-resolved bucket — prefer that. docs/lld.md §6.
     */
    @Override
    public boolean record(SaleEvent event) {
        return record(event, event.eventTime().atZone(ZoneOffset.UTC).toLocalDate());
    }

    @Override
    public boolean record(SaleEvent event, LocalDate bucketDate) {
        appendRawLog(event);
        int rows =
                jdbc.update(
                        INSERT_EVENT,
                        event.tenantId(),
                        event.orderId(),
                        event.categoryId(),
                        event.amount(),
                        event.currency(),
                        event.eventType().name(),
                        Timestamp.from(event.eventTime()),
                        Date.valueOf(bucketDate),
                        event.effectiveIdempotencyKey());
        return rows == 1;
    }

    /** Append the raw event as one NDJSON line; the raw log is the only precious local data (§6). */
    private void appendRawLog(SaleEvent event) {
        try {
            Files.createDirectories(rawLogFile.getParent());
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.write(
                    rawLogFile,
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append to raw log " + rawLogFile, e);
        }
    }
}
