package com.topsales.datagen;

import java.util.List;

/**
 * The committed synthetic-data spec (data/seed/seed-config.json). A fixed {@code globalSeed} plus
 * this catalog is the whole dataset — the generator regenerates byte-identical data for a given run
 * date, so no large CSV/SQL dump is committed. ADR-0010.
 */
public record SeedConfig(
        long globalSeed,
        String tenant,
        String currency,
        int historyDays,
        double trendAnnual,
        double returnRate,
        List<CategorySpec> categories,
        OutlierSpec outlier) {

    /** One demo category: baseline level, average order value, channel split, and sparsity. */
    public record CategorySpec(
            String id, double base, double aov, double onlineShare, boolean sparse) {}

    /** A single one-off spike on a non-HVE day (distinct from recurring seasonality). */
    public record OutlierSpec(String category, String channel, int daysAgo, double multiplier) {}
}
