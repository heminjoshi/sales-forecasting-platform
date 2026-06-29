package com.topsales.datagen.gen;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.EventType;
import com.topsales.common.domain.SaleEvent;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.SeedConfig.CategorySpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * The deterministic synthetic-demand core (ADR-0010). One value per
 * {@code (category, channel, day)} cell:
 *
 * <pre>value = base × channelShare × trend × weekly × monthly × hve(date,channel) × noise</pre>
 *
 * with a per-cell RNG seeded by {@code hash(globalSeed, tenant, category, channel, epochDay)} — so
 * generation is order-independent and reruns are byte-identical, and the live trickle (same model,
 * same day) is a natural continuation of the seeded history rather than a discontinuity.
 *
 * <p>Both load modes share this one core: {@link #generateAggregates()} emits pre-summed
 * {@link AggregateRow}s for the bulk backfill; {@link #generateEventsForDay} decomposes a day into
 * individual {@link SaleEvent}s for the realtime trickle.
 */
public final class SeasonalityModel {

    private final SeedConfig config;
    private final SeedConfig.SeasonalitySpec seasonality;
    private final HveCalendar hve;
    private final String tenantId;
    private final String currency;
    private final LocalDate windowStart;
    private final LocalDate windowEnd;
    private final LocalDate outlierDate;

    public SeasonalityModel(
            SeedConfig config,
            String tenantId,
            String currency,
            LocalDate windowStart,
            LocalDate windowEnd) {
        this.config = config;
        // Seasonality factors + HVE magnitudes are config (data/seed/seed-config.json), not constants:
        // weekly[index = DayOfWeek.getValue() - 1, Mon..Sun], monthly[index = month - 1].
        this.seasonality = config.seasonality();
        this.hve = new HveCalendar(config.hve());
        this.tenantId = tenantId;
        this.currency = currency;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.outlierDate =
                config.outlier() == null ? null : windowEnd.minusDays(config.outlier().daysAgo());
    }

    /** Pre-summed aggregate rows for every cell in {@code [windowStart, windowEnd]} (zeros skipped). */
    public List<AggregateRow> generateAggregates() {
        List<AggregateRow> rows = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(windowEnd); date = date.plusDays(1)) {
            for (CategorySpec cat : config.categories()) {
                for (Channel channel : Channel.values()) {
                    double gross = grossValue(cat, channel, date);
                    if (gross <= 0) {
                        continue; // sparse off-day
                    }
                    double net = gross * (1.0 - config.returnRate()); // signed returns netted in
                    rows.add(
                            new AggregateRow(
                                    tenantId,
                                    cat.id(),
                                    channel,
                                    date,
                                    money(net),
                                    orderCount(gross, cat),
                                    currency));
                }
            }
        }
        return rows;
    }

    /**
     * Individual events for {@code day} — the realtime trickle. Orders are bounded per cell so the
     * POST batch stays demo-sized; {@code nonce} makes order ids unique per invocation (so they are
     * applied, not deduped). A fraction of cells emit a signed RETURN.
     */
    public List<SaleEvent> generateEventsForDay(LocalDate day, Instant eventTime, String nonce) {
        List<SaleEvent> events = new ArrayList<>();
        for (CategorySpec cat : config.categories()) {
            for (Channel channel : Channel.values()) {
                double gross = grossValue(cat, channel, day);
                if (gross <= 0) {
                    continue;
                }
                int orders = Math.min(orderCount(gross, cat), 6);
                double each = gross / orders;
                for (int i = 0; i < orders; i++) {
                    String orderId = "trk-" + nonce + "-" + cat.id() + "-" + channel + "-" + i;
                    events.add(saleEvent(cat, channel, orderId, money(each), EventType.SALE, eventTime));
                }
                // Occasional return: a signed negative event (exercises idempotent signed netting).
                if (unit(cat, channel, day, "trickle-return") < config.returnRate() * 2) {
                    String orderId = "trk-" + nonce + "-" + cat.id() + "-" + channel + "-ret";
                    events.add(
                            saleEvent(
                                    cat, channel, orderId, money(-each), EventType.RETURN, eventTime));
                }
            }
        }
        return events;
    }

    /** The model value for one cell, including HVE, the one-off outlier, and the sparse gate. */
    public double grossValue(CategorySpec cat, Channel channel, LocalDate date) {
        if (cat.sparse() && unit(cat, channel, date, "sparse-gate") >= seasonality.sparseHitRate()) {
            return 0.0; // intermittent demand: most days are empty
        }
        double channelShare = channel == Channel.ONLINE ? cat.onlineShare() : 1.0 - cat.onlineShare();
        double years = ChronoUnit.DAYS.between(windowStart, date) / 365.0;
        double trend = 1.0 + config.trendAnnual() * years;
        double weekly =
                (channel == Channel.ONLINE ? seasonality.weeklyOnline() : seasonality.weeklyOffline())[
                        date.getDayOfWeek().getValue() - 1];
        double monthly = seasonality.monthly()[date.getMonthValue() - 1];
        double hveMultiplier = hve.multiplier(date, channel);
        // noiseBand width centered on 1.0: e.g. 0.2 → [0.9, 1.1).
        double noise = (1.0 - seasonality.noiseBand() / 2.0) + seasonality.noiseBand() * unit(cat, channel, date, "noise");

        double value = cat.base() * channelShare * trend * weekly * monthly * hveMultiplier * noise;

        if (isOutlier(cat, channel, date)) {
            value *= config.outlier().multiplier();
        }
        return value;
    }

    private boolean isOutlier(CategorySpec cat, Channel channel, LocalDate date) {
        return outlierDate != null
                && cat.id().equals(config.outlier().category())
                && channel.name().equals(config.outlier().channel())
                && date.equals(outlierDate);
    }

    private int orderCount(double gross, CategorySpec cat) {
        return Math.max(1, (int) Math.round(gross / cat.aov()));
    }

    private SaleEvent saleEvent(
            CategorySpec cat,
            Channel channel,
            String orderId,
            BigDecimal amount,
            EventType type,
            Instant eventTime) {
        return new SaleEvent(
                tenantId,
                orderId,
                cat.id(),
                channel,
                amount,
                currency,
                type,
                eventTime,
                null);
    }

    /** Per-cell uniform random in [0,1), seeded deterministically by the cell coordinates + salt. */
    private double unit(CategorySpec cat, Channel channel, LocalDate date, String salt) {
        long h = 0x9E3779B97F4A7C15L;
        h = mix(h, config.globalSeed());
        h = mix(h, tenantId.hashCode());
        h = mix(h, cat.id().hashCode());
        h = mix(h, channel.ordinal());
        h = mix(h, date.toEpochDay());
        h = mix(h, salt.hashCode());
        return new SplittableRandom(h).nextDouble();
    }

    private static long mix(long h, long x) {
        h ^= x;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= (h >>> 31);
        return h;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
