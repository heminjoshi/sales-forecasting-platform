package com.topsales.forecast.eval;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Window;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.gen.SeasonalityModel;
import com.topsales.forecast.model.HoltWintersForecaster;
import com.topsales.forecast.model.SeasonalNaiveForecaster;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * Orchestrates the WAPE backtest end to end, on the committed seed, with no Spring and no DB:
 * regenerate the deterministic series in-memory → group by {@link SeriesKey} → for every
 * {@code series × forecaster × fold} align the daily forecast against zero-filled actuals and
 * accumulate → {@link EvalResult}.
 *
 * <h2>Reproducibility</h2>
 * The backtest window is <b>fixed</b> ({@link #WINDOW_END} is a hard-coded constant, never
 * {@code LocalDate.now()}) so the generated report is byte-identical on every run. The window start is
 * derived from {@code seed-config.json}'s {@code historyDays}.
 *
 * <h2>pointValue → per-day</h2>
 * Each forecaster returns, per horizon {@code h}, the predicted <em>cumulative</em> sum of the next
 * {@code h} days. Requesting horizons {@code {1..7}} and differencing
 * ({@code f(i) = pointValue(i) − pointValue(i−1)}, {@code pointValue(0)=0}) recovers the per-day
 * forecast, which is what aligns against the per-day actual on {@code trainEnd + i}.
 */
public final class BacktestRunner {

    /** Fixed backtest window end — a constant for determinism (NOT {@code LocalDate.now()}). */
    public static final LocalDate WINDOW_END = LocalDate.of(2026, 6, 1);

    /** Per-day horizons {1..7}; differenced into a daily forecast. */
    static final int[] DAILY_HORIZONS = {1, 2, 3, 4, 5, 6, 7};

    public static final String MODEL_NAIVE = "SeasonalNaive";
    public static final String MODEL_HW = "HoltWinters";

    // Forecaster defaults — mirror topsales.forecast.* (see application.yml / ForecastWiring).
    private static final int SEASON_LENGTH = 7;
    private static final double Z = 1.28;
    private static final double HIGH_MAX = 0.15;
    private static final double MEDIUM_MAX = 0.40;
    private static final double HW_ALPHA = 0.3;
    private static final double HW_BETA = 0.1;
    private static final double HW_GAMMA = 0.3;

    private final Path repoRoot;
    private final CvConfig cv;

    public BacktestRunner(Path repoRoot, CvConfig cv) {
        this.repoRoot = repoRoot;
        this.cv = cv;
    }

    public static BacktestRunner withDefaults(Path repoRoot) {
        return new BacktestRunner(repoRoot, CvConfig.defaults());
    }

    /** A forecaster paired with the name it is reported under. */
    private record Named(String name, Forecaster forecaster) {}

    public EvalResult run() {
        SeedConfig config = loadSeedConfig();
        LocalDate windowEnd = WINDOW_END;
        LocalDate windowStart = windowEnd.minusDays(config.historyDays() - 1L);

        // --- Regenerate every series in-memory (deterministic; currency irrelevant to forecasting). ---
        Map<SeriesKey, List<AggregateRow>> grouped = new TreeMap<>();
        for (String tenant : config.tenants()) {
            List<AggregateRow> rows =
                    new SeasonalityModel(config, tenant, "USD", windowStart, windowEnd)
                            .generateAggregates();
            for (AggregateRow r : rows) {
                SeriesKey key = new SeriesKey(r.tenantId(), r.categoryId(), r.channel());
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
        }

        List<Fold> folds = FoldSplitter.split(windowStart, windowEnd, cv);

        List<Named> forecasters =
                List.of(
                        new Named(
                                MODEL_NAIVE,
                                new SeasonalNaiveForecaster(SEASON_LENGTH, Z, HIGH_MAX, MEDIUM_MAX)),
                        new Named(
                                MODEL_HW,
                                new HoltWintersForecaster(
                                        HW_ALPHA, HW_BETA, HW_GAMMA, SEASON_LENGTH, Z, HIGH_MAX,
                                        MEDIUM_MAX)));

        List<SegmentMetrics> segments = new ArrayList<>();
        Map<String, WapeCalculator> overallPool = new LinkedHashMap<>();
        Map<String, Integer> overallSegments = new LinkedHashMap<>();
        for (Named nf : forecasters) {
            overallPool.put(nf.name(), new WapeCalculator());
            overallSegments.put(nf.name(), 0);
        }

        // --- Score every series × forecaster × fold. ---
        for (Map.Entry<SeriesKey, List<AggregateRow>> e : grouped.entrySet()) {
            SeriesKey key = e.getKey();
            ActualSeries series = ActualSeries.from(key, e.getValue());
            for (Named nf : forecasters) {
                WapeCalculator seg = new WapeCalculator();
                for (Fold fold : folds) {
                    scoreFold(series, nf.forecaster(), key, fold, seg);
                }
                SegmentMetrics m = seg.toMetrics(key, nf.name());
                segments.add(m);
                if (m.defined()) {
                    overallPool.get(nf.name()).merge(seg);
                    overallSegments.merge(nf.name(), 1, Integer::sum);
                }
            }
        }

        // --- Section A: stable total order (forecaster, then series). ---
        segments.sort(
                (a, b) -> {
                    int c = a.model().compareTo(b.model());
                    return c != 0 ? c : a.key().compareTo(b.key());
                });

        List<EvalResult.Comparison> comparisons = buildComparisons(grouped.keySet(), segments);

        List<EvalResult.PooledMetrics> overall = new ArrayList<>();
        for (Named nf : forecasters) {
            WapeCalculator pool = overallPool.get(nf.name());
            overall.add(
                    new EvalResult.PooledMetrics(
                            nf.name(),
                            pool.wape(),
                            pool.bias(),
                            pool.count(),
                            overallSegments.get(nf.name())));
        }

        return new EvalResult(
                segments, comparisons, overall, cv, folds.size(), windowStart, windowEnd);
    }

    /** Accumulate one fold's seven aligned (actual, forecast) day-pairs into {@code seg}. */
    private void scoreFold(
            ActualSeries series, Forecaster forecaster, SeriesKey key, Fold fold, WapeCalculator seg) {
        List<AggregateRow> train = series.trainUpTo(fold.trainEnd());
        ForecastContext ctx =
                new ForecastContext(key.tenantId(), key.categoryId(), DAILY_HORIZONS, Window.WEEK);
        List<ForecastValue> values = forecaster.forecast(train, ctx);

        // pointValue is the cumulative sum of the next h days; index by horizon (order-independent).
        BigDecimal[] cumulative = new BigDecimal[DAILY_HORIZONS.length + 1];
        cumulative[0] = BigDecimal.ZERO;
        for (ForecastValue v : values) {
            if (v.horizon() >= 1 && v.horizon() <= DAILY_HORIZONS.length) {
                cumulative[v.horizon()] = v.pointValue();
            }
        }
        for (int i = 1; i <= DAILY_HORIZONS.length; i++) {
            BigDecimal daily = cumulative[i].subtract(cumulative[i - 1]); // difference back to per-day
            BigDecimal actual = series.sumOn(fold.trainEnd().plusDays(i)); // zero-filled
            seg.add(actual, daily);
        }
    }

    /** Pair naive vs HW per series; winner = lower WAPE (defined wins over undefined; else tie). */
    private static List<EvalResult.Comparison> buildComparisons(
            Iterable<SeriesKey> keys, List<SegmentMetrics> segments) {
        Map<SeriesKey, SegmentMetrics> naive = new TreeMap<>();
        Map<SeriesKey, SegmentMetrics> hw = new TreeMap<>();
        for (SegmentMetrics m : segments) {
            (m.model().equals(MODEL_NAIVE) ? naive : hw).put(m.key(), m);
        }
        List<EvalResult.Comparison> out = new ArrayList<>();
        TreeMap<SeriesKey, Boolean> ordered = new TreeMap<>();
        for (SeriesKey k : keys) {
            ordered.put(k, Boolean.TRUE);
        }
        for (SeriesKey k : ordered.keySet()) {
            SegmentMetrics n = naive.get(k);
            SegmentMetrics h = hw.get(k);
            out.add(new EvalResult.Comparison(k, n, h, winner(n, h)));
        }
        return out;
    }

    private static String winner(SegmentMetrics naive, SegmentMetrics hw) {
        boolean nd = naive != null && naive.defined();
        boolean hd = hw != null && hw.defined();
        if (!nd && !hd) {
            return "n/a";
        }
        if (nd && !hd) {
            return MODEL_NAIVE;
        }
        if (hd && !nd) {
            return MODEL_HW;
        }
        if (hw.wape() < naive.wape()) {
            return MODEL_HW;
        }
        if (naive.wape() < hw.wape()) {
            return MODEL_NAIVE;
        }
        return "tie";
    }

    private SeedConfig loadSeedConfig() {
        Path configPath = repoRoot.resolve("data/seed/seed-config.json");
        try {
            JsonMapper mapper = JsonMapper.builder().build();
            return mapper.readValue(Files.readString(configPath), SeedConfig.class);
        } catch (IOException ex) {
            throw new UncheckedIOException("failed to read seed config at " + configPath, ex);
        }
    }
}
