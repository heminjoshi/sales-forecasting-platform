package com.topsales.forecast.batch;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.Mode;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.domain.Window;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.common.forecast.ServingKey;
import com.topsales.common.forecast.ServingRow;
import com.topsales.common.forecast.ServingTableRepository;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * The forecast batch (Phase-3 decisions 5–8). For every tenant it loads the trailing history, forecasts
 * each {@code (category, channel)} series once over the {@code {7,30,365}}-day horizons, rolls the
 * channel grain up to {@code all}, ranks each {@code (window, channel)} partition independently, and
 * writes the ranked rows as a new, atomically-swapped serving version.
 *
 * <p>Per tenant this produces exactly <b>9 serving-key writes</b> (3 windows × 3 channels:
 * online/offline/all). The math seam ({@code forecaster}) is called once per series with all horizons,
 * per its contract; the rollup, {@code delta_vs_prior}, and ranking are pure package-private helpers so
 * they unit-test without a database.
 */
@Component
public class ForecasterJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ForecasterJob.class);

    /** The three serving windows, in the order their horizons are requested from the forecaster. */
    private static final Window[] WINDOWS = {Window.WEEK, Window.MONTH, Window.YEAR};

    /** Scale for the user-facing {@code delta_vs_prior} ratio. */
    private static final int DELTA_SCALE = 4;

    private final Forecaster forecaster;
    private final AggregateRepository aggregates;
    private final TenantConfigRepository tenantConfig;
    private final ServingTableRepository servingTable;
    private final CacheVersionBumper cacheVersionBumper;
    private final TopsalesProperties props;

    private final int historyDays;
    private final int topN;
    private final int[] horizons;
    private final Map<Integer, Window> horizonToWindow;

    public ForecasterJob(
            Forecaster forecaster,
            AggregateRepository aggregates,
            TenantConfigRepository tenantConfig,
            ServingTableRepository servingTable,
            CacheVersionBumper cacheVersionBumper,
            TopsalesProperties props) {
        this.forecaster = forecaster;
        this.aggregates = aggregates;
        this.tenantConfig = tenantConfig;
        this.servingTable = servingTable;
        this.cacheVersionBumper = cacheVersionBumper;
        this.props = props;
        this.historyDays = props.forecast().historyDays();
        this.topN = props.forecast().servingTopN();

        TopsalesProperties.WindowDays wd = props.windowDays();
        this.horizons =
                new int[] {
                    wd.forWindow(Window.WEEK), wd.forWindow(Window.MONTH), wd.forWindow(Window.YEAR)
                };
        Map<Integer, Window> h2w = new LinkedHashMap<>();
        for (Window w : WINDOWS) {
            h2w.put(wd.forWindow(w), w);
        }
        this.horizonToWindow = h2w;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant asOf = Instant.now();
        long startNanos = System.nanoTime();
        List<String> tenants = tenantConfig.allTenantIds();
        log.info("Forecast batch starting: tenants={}", tenants.size());
        int totalWrites = 0;
        boolean success = false;
        try {
            for (String tenant : tenants) {
                totalWrites += runTenant(tenant, asOf);
            }
            log.info("Forecast batch complete: tenants={}", tenants.size());
            success = true;
        } finally {
            // One structured, parse-friendly metrics line per run (success or failure). The forecast
            // module pulls in no micrometer dep — this log is the batch's only "metric" surface; a log
            // pipeline (or the verify layer) lifts batch_success / durationMs / pkWrites from it.
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info(
                    "batch metrics: batch_success={} tenants={} durationMs={} pkWrites={}",
                    success,
                    tenants.size(),
                    durationMs,
                    totalWrites);
        }
    }

    /**
     * Forecast, roll up, rank, and write the 9 serving keys for one tenant. Returns the number of
     * serving-key writes (always 9 on success) so {@link #run} can total {@code pkWrites}.
     */
    int runTenant(String tenant, Instant asOf) {
        // Per-tenant MDC so every log line emitted while this tenant is processing carries tenantId
        // (structured-log correlation); removed in finally so the thread never leaks the tag.
        MDC.put("tenantId", tenant);
        try {
            ZoneId tz =
                    tenantConfig.find(tenant).map(TenantConfig::timezone).orElse(ZoneOffset.UTC);
            LocalDate today = LocalDate.now(tz);
            LocalDate from = today.minusDays(historyDays - 1L);

            List<AggregateRow> rows =
                    aggregates.rangeByCategory(tenant, from, today, ChannelFilter.ALL);
            Map<SeriesKey, List<AggregateRow>> bySeries = groupBySeries(rows);

            // Call the forecaster once per series (contract: one call, all horizons), index by window.
            Map<SeriesKey, Map<Window, Prediction>> forecasts = new LinkedHashMap<>();
            for (Map.Entry<SeriesKey, List<AggregateRow>> e : bySeries.entrySet()) {
                SeriesKey key = e.getKey();
                List<ForecastValue> values =
                        forecaster.forecast(
                                e.getValue(),
                                new ForecastContext(
                                        tenant, key.categoryId(), horizons, Window.WEEK));
                forecasts.put(key, indexByWindow(values));
            }

            int writes = 0;
            for (Window window : WINDOWS) {
                int days = props.windowDays().forWindow(window);
                for (Channel channel : Channel.values()) {
                    List<ServingRow> ranked =
                            rankChannel(window, channel, forecasts, bySeries, today, days);
                    servingTable.writeVersionAndSwap(
                            ServingKey.of(tenant, window, Mode.FORECAST, filterFor(channel)),
                            ranked,
                            asOf);
                    writes++;
                }
                List<ServingRow> rankedAll = rankAll(window, forecasts, bySeries, today, days);
                servingTable.writeVersionAndSwap(
                        ServingKey.of(tenant, window, Mode.FORECAST, ChannelFilter.ALL),
                        rankedAll,
                        asOf);
                writes++;
            }
            // All 9 serving keys for this tenant are written — invalidate its cache so the new
            // forecasts are served immediately (event-driven, O(1); fails open if Redis is down,
            // docs/lld.md §7).
            cacheVersionBumper.bump(tenant);
            log.info(
                    "Forecast batch: tenant={} series={} pkWrites={}",
                    tenant,
                    bySeries.size(),
                    writes);
            return writes;
        } finally {
            MDC.remove("tenantId");
        }
    }

    // --- pure helpers (package-private for unit testing) ----------------------------------------

    /** Group the flat range scan into one history list per {@code (category, channel)} series. */
    Map<SeriesKey, List<AggregateRow>> groupBySeries(List<AggregateRow> rows) {
        Map<SeriesKey, List<AggregateRow>> out = new LinkedHashMap<>();
        for (AggregateRow r : rows) {
            out.computeIfAbsent(new SeriesKey(r.categoryId(), r.channel()), k -> new ArrayList<>())
                    .add(r);
        }
        return out;
    }

    /** Map the forecaster's per-horizon outputs onto serving windows by matching horizon days. */
    Map<Window, Prediction> indexByWindow(List<ForecastValue> values) {
        Map<Window, Prediction> out = new EnumMap<>(Window.class);
        for (ForecastValue v : values) {
            Window w = horizonToWindow.get(v.horizon());
            if (w != null) {
                out.put(
                        w,
                        new Prediction(
                                v.pointValue(), v.intervalLow(), v.intervalHigh(), v.confidence()));
            }
        }
        return out;
    }

    /** Rank a single concrete channel's categories for one window. */
    List<ServingRow> rankChannel(
            Window window,
            Channel channel,
            Map<SeriesKey, Map<Window, Prediction>> forecasts,
            Map<SeriesKey, List<AggregateRow>> bySeries,
            LocalDate today,
            int days) {
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<SeriesKey, Map<Window, Prediction>> e : forecasts.entrySet()) {
            SeriesKey key = e.getKey();
            if (key.channel() != channel) {
                continue;
            }
            Prediction p = e.getValue().get(window);
            if (p == null) {
                continue;
            }
            BigDecimal prior = trailingActual(bySeries.get(key), today, days);
            candidates.add(
                    new Candidate(
                            key.categoryId(),
                            p.point(),
                            p.low(),
                            p.high(),
                            p.confidence(),
                            delta(p.point(), prior)));
        }
        return rank(candidates, topN);
    }

    /**
     * Roll the channel grain up to {@code all} <b>before</b> ranking (sum points/bounds, worst
     * confidence; {@code delta} against the summed prior actual), then rank the {@code all} partition.
     */
    List<ServingRow> rankAll(
            Window window,
            Map<SeriesKey, Map<Window, Prediction>> forecasts,
            Map<SeriesKey, List<AggregateRow>> bySeries,
            LocalDate today,
            int days) {
        Map<String, Prediction> rolled = new LinkedHashMap<>();
        Map<String, BigDecimal> priorByCat = new LinkedHashMap<>();
        for (Map.Entry<SeriesKey, Map<Window, Prediction>> e : forecasts.entrySet()) {
            SeriesKey key = e.getKey();
            Prediction p = e.getValue().get(window);
            if (p == null) {
                continue;
            }
            rolled.merge(key.categoryId(), p, ForecasterJob::sum);
            priorByCat.merge(
                    key.categoryId(),
                    trailingActual(bySeries.get(key), today, days),
                    BigDecimal::add);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, Prediction> e : rolled.entrySet()) {
            Prediction p = e.getValue();
            candidates.add(
                    new Candidate(
                            e.getKey(),
                            p.point(),
                            p.low(),
                            p.high(),
                            p.confidence(),
                            delta(p.point(), priorByCat.get(e.getKey()))));
        }
        return rank(candidates, topN);
    }

    /** Sort desc by point (tie-break {@code categoryId} asc), take top-N, assign contiguous ranks. */
    List<ServingRow> rank(List<Candidate> candidates, int limit) {
        candidates.sort(
                Comparator.comparing(Candidate::point)
                        .reversed()
                        .thenComparing(Candidate::categoryId));
        List<ServingRow> out = new ArrayList<>(Math.min(candidates.size(), limit));
        int rank = 1;
        for (Candidate c : candidates) {
            if (rank > limit) {
                break;
            }
            out.add(
                    new ServingRow(
                            rank++, c.categoryId(), c.point(), c.low(), c.high(), c.confidence(),
                            c.delta()));
        }
        return out;
    }

    /** Trailing-{@code days} actual sum for one series (inclusive of {@code today}). */
    static BigDecimal trailingActual(List<AggregateRow> series, LocalDate today, int days) {
        LocalDate cutoff = today.minusDays(days - 1L);
        BigDecimal sum = BigDecimal.ZERO;
        for (AggregateRow r : series) {
            LocalDate d = r.bucketDate();
            if (!d.isBefore(cutoff) && !d.isAfter(today)) {
                sum = sum.add(r.sumAmount());
            }
        }
        return sum;
    }

    /** {@code (point − prior) / prior}; {@code null} when the prior actual is zero (DR-8). */
    static BigDecimal delta(BigDecimal point, BigDecimal prior) {
        if (prior == null || prior.signum() == 0) {
            return null;
        }
        return point.subtract(prior).divide(prior, DELTA_SCALE, RoundingMode.HALF_UP);
    }

    /** Sum two channel predictions into the {@code all} rollup: bounds add, confidence is the worst. */
    static Prediction sum(Prediction a, Prediction b) {
        return new Prediction(
                a.point().add(b.point()),
                a.low().add(b.low()),
                a.high().add(b.high()),
                worst(a.confidence(), b.confidence()));
    }

    /** The worse (lower) of two confidences; {@code Confidence} is ordered HIGH &gt; MEDIUM &gt; LOW. */
    static Confidence worst(Confidence a, Confidence b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private static ChannelFilter filterFor(Channel channel) {
        return channel == Channel.ONLINE ? ChannelFilter.ONLINE : ChannelFilter.OFFLINE;
    }

    /** A {@code (category, channel)} series identity used to group history and index forecasts. */
    record SeriesKey(String categoryId, Channel channel) {}

    /** One window's forecast for a series (or rolled-up {@code all}): point, bounds, confidence. */
    record Prediction(
            BigDecimal point, BigDecimal low, BigDecimal high, Confidence confidence) {}

    /** A ranking candidate before rank assignment. */
    record Candidate(
            String categoryId,
            BigDecimal point,
            BigDecimal low,
            BigDecimal high,
            Confidence confidence,
            BigDecimal delta) {}
}
