package com.topsales.ingestion.repo;

/**
 * Dead-letter port for malformed/un-ingestable events. Captured and counted, never retried into the
 * pipeline (aws: SQS DLQ). docs/lld.md §3.1, §6.
 */
public interface QuarantineRepository {

    /**
     * Persist a rejected event's raw JSON with the failure reason.
     *
     * @param tenantId authoritative tenant if known; may be {@code null} when unresolvable
     * @param payload the raw rejected JSON
     * @param reason validation/parse failure detail
     */
    void quarantine(String tenantId, String payload, String reason);
}
