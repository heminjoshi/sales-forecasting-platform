package com.topsales.datagen;

import java.util.List;
import java.util.Map;

/**
 * The committed synthetic-data spec (data/seed/seed-config.json). A fixed {@code globalSeed} plus
 * this catalog is the whole dataset — the generator regenerates byte-identical data for a given run
 * date, so no large CSV/SQL dump is committed. ADR-0010. The optional per-tenant {@code archetypes}
 * + {@code tenantArchetypes} layer shapes each tenant's category mix and overall volume so the demo
 * tenants look visibly distinct.
 */
public record SeedConfig(
        long globalSeed,
        List<String> tenants,
        int historyDays,
        double trendAnnual,
        double returnRate,
        List<CategorySpec> categories,
        OutlierSpec outlier,
        SeasonalitySpec seasonality,
        HveSpec hve,
        Map<String, Archetype> archetypes,
        Map<String, String> tenantArchetypes) {

    /** One demo category: baseline level, average order value, channel split, and sparsity. */
    public record CategorySpec(
            String id, double base, double aov, double onlineShare, boolean sparse) {}

    /**
     * Per-tenant demand shaper: a global scale plus per-category multipliers — lets each tenant
     * have a distinct category mix without hand-authoring full profiles.
     */
    public record Archetype(double scale, Map<String, Double> weights) {}

    /** A single one-off spike on a non-HVE day (distinct from recurring seasonality). */
    public record OutlierSpec(String category, String channel, int daysAgo, double multiplier) {}

    /**
     * The repeating-seasonality tunables read by {@code SeasonalityModel}: per-day-of-week factors
     * (Mon..Sun, length 7), per-month factors (Jan..Dec, length 12), the multiplicative noise band
     * width (e.g. {@code 0.2} → noise in {@code [0.9, 1.1)}), and the sparse-category hit rate (the
     * fraction of {@code (category, channel, day)} cells an intermittent category fires on).
     */
    public record SeasonalitySpec(
            double[] weeklyOnline,
            double[] weeklyOffline,
            double[] monthly,
            double noiseBand,
            double sparseHitRate) {}

    /**
     * High-volume-event multipliers read by {@code HveCalendar} (the calendar anchors stay in code;
     * only the channel-split magnitudes are config). The December ramp interpolates linearly from
     * {@code decemberRampStart} (Dec 1) to {@code decemberRampEnd} (Dec 24), then dips to
     * {@code decemberPostDip} from Dec 26.
     */
    public record HveSpec(
            double blackFridayOffline,
            double blackFridayOnline,
            double cyberMondayOffline,
            double cyberMondayOnline,
            double primeDayOnline,
            double primeDayOffline,
            double decemberRampStart,
            double decemberRampEnd,
            double decemberPostDip) {}
}
