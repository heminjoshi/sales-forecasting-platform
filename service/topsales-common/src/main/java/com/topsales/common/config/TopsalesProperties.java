package com.topsales.common.config;

import com.topsales.common.domain.Window;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single central configuration surface for every tweakable variable on the read/serving side
 * (docs/lld.md §10). Binds the whole {@code topsales.*} tree from {@code application.yml} so values
 * like the dashboard's {@code k}, the window definitions, the validation bounds, and the cache knobs
 * live in one named place instead of scattered constants.
 *
 * <p>Lives in {@code topsales-common} so every module shares it. The bootable app
 * ({@code TopSalesApplication}) registers it via {@code @ConfigurationPropertiesScan}. Phase-3
 * forecast params should be added under {@link Forecast} here, not as new constants.
 *
 * <p>Each nested group is domain-prefixed so names can't be confused across concerns
 * ({@code read.k-default} vs {@code window-days.week} vs {@code forecast.history-days}).
 */
@ConfigurationProperties(prefix = "topsales")
public record TopsalesProperties(
        Read read,
        WindowDays windowDays,
        Forecast forecast,
        Cache cache,
        Insight insight,
        Rawlog rawlog) {

    /**
     * Read/serving endpoint tunables: the dashboard's {@code k} choices and the request-param
     * defaults applied when a caller omits {@code mode}/{@code window}/{@code channel}/{@code k}.
     * Enum-valued defaults are kept as their lowercase wire strings and parsed through the enums'
     * {@code from(...)} factories (so case-insensitive parse behavior is preserved).
     */
    public record Read(
            int kDefault,
            int kMin,
            int kMax,
            List<Integer> kOptions,
            String windowDefault,
            String modeDefault,
            String channelDefault) {}

    /**
     * Trailing length in calendar days for each {@link Window}. Calendar-day counts (not calendar
     * months) keep the window math timezone-correct and trivially testable.
     */
    public record WindowDays(int week, int month, int year) {

        /** Days for {@code window}, keyed off its lowercase wire name ({@link Window#wire()}). */
        public int forWindow(Window window) {
            return switch (window) {
                case WEEK -> week;
                case MONTH -> month;
                case YEAR -> year;
            };
        }
    }

    /**
     * Forecast knobs (Phase 3). {@code servingTopN} = ranked rows written per serving pk;
     * {@code versionKeep} = serving versions retained for rollback. The nested groups tune the
     * Holt-Winters smoothing, the prediction-interval width → confidence mapping, and the
     * time-series-CV / WAPE evaluation. All defaults live in {@code application.yml}.
     */
    public record Forecast(
            Duration freshnessSlo,
            int historyDays,
            int servingTopN,
            int versionKeep,
            HoltWinters holtWinters,
            Interval interval,
            Eval eval) {

        /** Holt-Winters triple-exponential-smoothing params (additive); {@code seasonLength} in days. */
        public record HoltWinters(double alpha, double beta, double gamma, int seasonLength) {}

        /**
         * Prediction interval + confidence mapping: {@code z} is the normal quantile (1.28 ≈ 80%);
         * relative half-width below {@code confidenceHighMax} → HIGH, below {@code confidenceMediumMax}
         * → MEDIUM, else LOW.
         */
        public record Interval(double z, double confidenceHighMax, double confidenceMediumMax) {}

        /**
         * Expanding-window time-series-CV backtest: initial train length, test horizon, step, and a
         * fold cap; plus the WAPE regression-guard thresholds (dense series must beat
         * {@code wapeDenseMax}; the sparse category is expected above {@code wapeSparseMin}).
         */
        public record Eval(
                int initialTrainDays,
                int testHorizonDays,
                int stepDays,
                int maxFolds,
                double wapeDenseMax,
                double wapeSparseMin) {}
    }

    /**
     * Read-through cache knobs (Phase 4 / Redis, docs/lld.md §7). {@code baseTtl} ± {@code jitterPct}%
     * is the per-key expiry; {@code lockTtl} bounds the single-flight lease so a crashed leader cannot
     * wedge a hot key.
     */
    public record Cache(Duration baseTtl, int jitterPct, Duration lockTtl) {}

    /**
     * GenAI insight knobs (Phase 5). {@code enabled} gates insight generation entirely (off → the
     * response's {@code insight} stays null and the dashboard hides the line). {@code provider}
     * selects the impl behind the {@code InsightGenerator} seam — {@code template} is the deterministic
     * local floor, {@code bedrock} is the designed cloud impl (selected via {@code @ConditionalOnProperty}
     * + {@code @Primary}, not a Spring profile). {@code modelId} is the Bedrock model identifier read
     * only by the Bedrock impl. {@code timeout} bounds a generation call before falling back to the
     * template, so a slow/failing model never stalls a read.
     */
    public record Insight(boolean enabled, String provider, String modelId, Duration timeout) {}

    /** Local immutable raw-event-log directory (the S3 stand-in). */
    public record Rawlog(String dir) {}
}
