package com.topsales.common.forecast;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Port over the precomputed serving table (the only coupling between the forecast plane and the read
 * path, DR-1). The batch (Phase 3) writes versioned rows and atomically swaps the active pointer; the
 * read path (Phase 4) reads the active version. Built impl: JdbcServingTableRepository (Postgres);
 * designed: DynamoDB behind the same port. docs/lld.md §3, §8.
 */
public interface ServingTableRepository {

    /** The active-version ranked rows for a serving {@code pk}, or empty if none written yet. */
    Optional<ServingResult> readActive(String pk);

    /**
     * Insert {@code rows} as a new version ({@code max(version)+1}) for {@code pk}, then atomically
     * flip {@code serving_active_version} to it (last-good stays intact until the swap); old versions
     * beyond the retained window are pruned. Returns the new active version. Safe to re-run.
     */
    int writeVersionAndSwap(String pk, List<ServingRow> rows, Instant asOf);
}
