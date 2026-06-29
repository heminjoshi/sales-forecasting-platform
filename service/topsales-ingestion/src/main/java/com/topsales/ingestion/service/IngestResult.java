package com.topsales.ingestion.service;

/**
 * Outcome counters for an ingest request, returned as the {@code 202} body
 * {@code {received, applied, deduped, quarantined}}. docs/lld.md §3.1.
 *
 * @param received total events seen
 * @param applied newly recorded and rolled into the aggregate
 * @param deduped recognized duplicates (idempotency gate), no aggregate change
 * @param quarantined rejected (validation/parse/unknown tenant), never retried
 */
public record IngestResult(int received, int applied, int deduped, int quarantined) {

    public static final IngestResult EMPTY = new IngestResult(0, 0, 0, 0);

    /** Element-wise sum, for accumulating a batch. */
    public IngestResult plus(IngestResult other) {
        return new IngestResult(
                received + other.received,
                applied + other.applied,
                deduped + other.deduped,
                quarantined + other.quarantined);
    }
}
