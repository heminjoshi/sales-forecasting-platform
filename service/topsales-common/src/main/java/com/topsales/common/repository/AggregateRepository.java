package com.topsales.common.repository;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.ChannelFilter;

import java.time.LocalDate;
import java.util.List;

/**
 * Port over the aggregates rollup. Write side (ingestion) is an additive upsert; read side (api)
 * is a keyed range scan the actuals path sums per category. Built impl: JdbcAggregateRepository
 * (Postgres); designed: Aurora. docs/lld.md §4.
 */
public interface AggregateRepository {

    /** Additive upsert of one event's contribution (ON CONFLICT DO UPDATE +=). Returns rows affected. */
    int upsertAdditive(AggregateDelta delta);

    /**
     * Bulk overwrite-upsert of pre-summed rows (the trusted {@code make seed} backfill, ADR-0010).
     * Sets {@code sum_amount}/{@code order_count} directly (not additive), so re-seeding converges
     * to an identical state. Returns the per-row update counts.
     */
    int[] bulkUpsert(List<AggregateRow> rows);

    /**
     * All aggregate rows for a tenant within {@code [from, to]} (inclusive), for windowed ranking.
     * {@code channel} selects one channel or {@link ChannelFilter#ALL} (both channels' rows, which
     * the caller sums per category to the {@code all} rollup).
     */
    List<AggregateRow> rangeByCategory(
            String tenantId, LocalDate from, LocalDate to, ChannelFilter channel);
}
