package com.topsales.ingestion.repo;

import com.topsales.common.domain.SaleEvent;
import com.topsales.common.repository.EventLedger;

import java.time.LocalDate;

/**
 * Ingestion-local extension of the {@link EventLedger} port that accepts the already-resolved
 * tenant-local {@code bucketDate}.
 *
 * <p><b>Why this exists:</b> the generic {@link EventLedger#record(SaleEvent)} port cannot compute
 * the persisted {@code bucket_date} on its own — {@code SaleEvent} carries only a UTC
 * {@code eventTime}, and the tenant-local calendar day (A6) needs the tenant's {@code ZoneId}.
 * {@code IngestionService} already loads {@code TenantConfig} (to reject unknown tenants), so it
 * resolves the bucket once and passes it in here, avoiding a second tz lookup inside the ledger.
 * The base one-arg port method stays intact for generic/designed (KCL) callers.
 */
public interface BucketingEventLedger extends EventLedger {

    /**
     * Append the raw event to the immutable raw log, then insert into {@code events}
     * (ON CONFLICT (idempotency_key) DO NOTHING) using {@code bucketDate} for {@code bucket_date}.
     *
     * @return {@code true} only when a new row was inserted; {@code false} on a duplicate (skip the
     *     aggregate upsert).
     */
    boolean record(SaleEvent event, LocalDate bucketDate);
}
