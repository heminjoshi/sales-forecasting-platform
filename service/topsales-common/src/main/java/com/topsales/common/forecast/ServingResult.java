package com.topsales.common.forecast;

import java.time.Instant;
import java.util.List;

/** The active-version top-k read from the serving table: the rows plus their version and as-of. */
public record ServingResult(
        List<ServingRow> rows,
        int version,
        Instant asOf) {
}
