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
        Read read, WindowDays windowDays, Forecast forecast, Cache cache, Rawlog rawlog) {

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

    /** Forecast freshness/history knobs. Phase-3/4 forecast params extend this group. */
    public record Forecast(Duration freshnessSlo, int historyDays) {}

    /** Read-through cache knobs (placeholders until Phase 4 / Redis). */
    public record Cache(Duration baseTtl, int jitterPct) {}

    /** Local immutable raw-event-log directory (the S3 stand-in). */
    public record Rawlog(String dir) {}
}
