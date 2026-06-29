package com.topsales.common.repository;

import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;

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

    /** All aggregate rows for a tenant within {@code [from, to]} (inclusive), for windowed ranking. */
    List<AggregateRow> rangeByCategory(String tenantId, LocalDate from, LocalDate to);
}
