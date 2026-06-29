package com.topsales.forecast.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.config.TopsalesProperties;
import com.topsales.common.domain.AggregateDelta;
import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.common.domain.ChannelFilter;
import com.topsales.common.domain.Confidence;
import com.topsales.common.domain.TenantConfig;
import com.topsales.common.forecast.ForecastContext;
import com.topsales.common.forecast.ForecastValue;
import com.topsales.common.forecast.Forecaster;
import com.topsales.common.forecast.ServingResult;
import com.topsales.common.forecast.ServingRow;
import com.topsales.common.forecast.ServingTableRepository;
import com.topsales.common.repository.AggregateRepository;
import com.topsales.common.repository.TenantConfigRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ForecasterJob} with hand fakes (no DB). The fixture is 3 categories × 2
 * channels over 14 days, ending today, with a deterministic fake forecaster (point = series sum,
 * confidence keyed by channel) so rollup, ranking, {@code delta_vs_prior}, and the 9-pk write contract
 * can all be asserted by hand.
 */
class ForecasterJobTest {

    private static final String TENANT = "t1";
    private static final ZoneId ZONE = ZoneOffset.UTC;

    private CapturingServingTable serving;
    private ForecasterJob job;

    @BeforeEach
    void setUp() {
        serving = new CapturingServingTable();
        job =
                new ForecasterJob(
                        new SumForecaster(),
                        new FixtureAggregates(),
                        new SingleTenantConfig(),
                        serving,
                        props());
    }

    @Test
    void writesNinePartitionKeysPerTenant() {
        job.run(null);

        assertThat(serving.calls).isEqualTo(9);
        assertThat(serving.writes.keySet())
                .containsExactlyInAnyOrder(
                        "t1#week#forecast#online",
                        "t1#week#forecast#offline",
                        "t1#week#forecast#all",
                        "t1#month#forecast#online",
                        "t1#month#forecast#offline",
                        "t1#month#forecast#all",
                        "t1#year#forecast#online",
                        "t1#year#forecast#offline",
                        "t1#year#forecast#all");
    }

    @Test
    void ranksDescendingWithTieBreakAndContiguousRanks() {
        job.run(null);

        // online: cat-A=140, cat-B=140 (tie -> categoryId asc), cat-C=14 dropped by top-N=2.
        List<ServingRow> online = serving.writes.get("t1#week#forecast#online");
        assertThat(online).hasSize(2);
        assertThat(online).extracting(ServingRow::rank).containsExactly(1, 2);
        assertThat(online).extracting(ServingRow::categoryId).containsExactly("cat-A", "cat-B");

        // offline: cat-B=140, cat-A=70 (desc), cat-C dropped.
        List<ServingRow> offline = serving.writes.get("t1#week#forecast#offline");
        assertThat(offline).extracting(ServingRow::categoryId).containsExactly("cat-B", "cat-A");
        assertThat(offline).extracting(ServingRow::rank).containsExactly(1, 2);
    }

    @Test
    void allValueIsSumOfChannelsAndConfidenceIsWorst() {
        job.run(null);

        List<ServingRow> all = serving.writes.get("t1#week#forecast#all");
        assertThat(all).hasSize(2);
        // cat-B = online 140 + offline 140 = 280 (rank 1); cat-A = 140 + 70 = 210 (rank 2).
        assertThat(all).extracting(ServingRow::categoryId).containsExactly("cat-B", "cat-A");
        assertThat(value(all, "cat-B")).isEqualByComparingTo("280");
        assertThat(value(all, "cat-A")).isEqualByComparingTo("210");
        // online HIGH + offline LOW -> worst = LOW for every rolled-up row.
        assertThat(all).allSatisfy(r -> assertThat(r.confidence()).isEqualTo(Confidence.LOW));
    }

    @Test
    void deltaVsPriorIsComputedAndNullWhenPriorIsZero() {
        job.run(null);

        // online cat-A: point 140 vs trailing-7 actual 70 -> (140-70)/70 = 1.0000.
        assertThat(delta(serving.writes.get("t1#week#forecast#online"), "cat-A"))
                .isEqualByComparingTo("1.0000");

        // offline cat-B: trailing-7 actual is 0 -> delta null.
        assertThat(delta(serving.writes.get("t1#week#forecast#offline"), "cat-B")).isNull();

        // all cat-B: point 280 vs summed prior (online 70 + offline 0 = 70) -> (280-70)/70 = 3.0000.
        assertThat(delta(serving.writes.get("t1#week#forecast#all"), "cat-B"))
                .isEqualByComparingTo("3.0000");
    }

    @Test
    void servingTopNTruncatesEachPartition() {
        job.run(null);
        // 3 categories, top-N = 2 -> every one of the 9 partitions keeps exactly 2 rows.
        assertThat(serving.writes.values()).allSatisfy(rows -> assertThat(rows).hasSize(2));
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static BigDecimal value(List<ServingRow> rows, String categoryId) {
        return row(rows, categoryId).value();
    }

    private static BigDecimal delta(List<ServingRow> rows, String categoryId) {
        return row(rows, categoryId).deltaVsPrior();
    }

    private static ServingRow row(List<ServingRow> rows, String categoryId) {
        return rows.stream()
                .filter(r -> r.categoryId().equals(categoryId))
                .findFirst()
                .orElseThrow();
    }

    private static TopsalesProperties props() {
        var windowDays = new TopsalesProperties.WindowDays(7, 30, 365);
        var forecast =
                new TopsalesProperties.Forecast(
                        Duration.ofHours(6),
                        730, // historyDays
                        2, // servingTopN
                        3, // versionKeep
                        null,
                        null,
                        null);
        return new TopsalesProperties(null, windowDays, forecast, null, null);
    }

    /** Fixture: 14 daily rows per series, ending today (so trailing-7 windows align with wall clock). */
    private static List<AggregateRow> fixture() {
        LocalDate today = LocalDate.now(ZONE);
        List<AggregateRow> rows = new ArrayList<>();
        addSeries(rows, today, "cat-A", Channel.ONLINE, constant(10));
        addSeries(rows, today, "cat-B", Channel.ONLINE, constant(10));
        addSeries(rows, today, "cat-C", Channel.ONLINE, constant(1));
        addSeries(rows, today, "cat-A", Channel.OFFLINE, constant(5));
        addSeries(rows, today, "cat-B", Channel.OFFLINE, firstHalfThenZero(20));
        addSeries(rows, today, "cat-C", Channel.OFFLINE, constant(1));
        return rows;
    }

    private static double[] constant(double v) {
        double[] a = new double[14];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** First 7 days = {@code v}, last 7 days = 0 -> nonzero full history but a zero trailing window. */
    private static double[] firstHalfThenZero(double v) {
        double[] a = new double[14];
        for (int i = 0; i < 7; i++) {
            a[i] = v;
        }
        return a;
    }

    private static void addSeries(
            List<AggregateRow> rows, LocalDate today, String cat, Channel channel, double[] vals) {
        int n = vals.length;
        for (int i = 0; i < n; i++) {
            LocalDate date = today.minusDays((long) (n - 1 - i));
            rows.add(
                    new AggregateRow(
                            TENANT, cat, channel, date, BigDecimal.valueOf(vals[i]), 1, "USD"));
        }
    }

    /** Deterministic forecaster: point = series sum for every horizon; confidence keyed by channel. */
    private static final class SumForecaster implements Forecaster {
        @Override
        public List<ForecastValue> forecast(List<AggregateRow> history, ForecastContext ctx) {
            BigDecimal sum =
                    history.stream()
                            .map(AggregateRow::sumAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            Confidence conf =
                    history.get(0).channel() == Channel.ONLINE ? Confidence.HIGH : Confidence.LOW;
            List<ForecastValue> out = new ArrayList<>();
            for (int h : ctx.horizons()) {
                out.add(new ForecastValue(h, sum, sum, sum, conf));
            }
            return out;
        }
    }

    private static final class FixtureAggregates implements AggregateRepository {
        @Override
        public int upsertAdditive(AggregateDelta delta) {
            return 0;
        }

        @Override
        public int[] bulkUpsert(List<AggregateRow> rows) {
            return new int[0];
        }

        @Override
        public List<AggregateRow> rangeByCategory(
                String tenantId, LocalDate from, LocalDate to, ChannelFilter channel) {
            return fixture();
        }
    }

    private static final class SingleTenantConfig implements TenantConfigRepository {
        @Override
        public Optional<TenantConfig> find(String tenantId) {
            return Optional.of(new TenantConfig(tenantId, ZONE, "USD"));
        }

        @Override
        public List<String> allTenantIds() {
            return List.of(TENANT);
        }
    }

    private static final class CapturingServingTable implements ServingTableRepository {
        private final Map<String, List<ServingRow>> writes = new java.util.LinkedHashMap<>();
        private int calls;

        @Override
        public Optional<ServingResult> readActive(String pk) {
            return Optional.empty();
        }

        @Override
        public int writeVersionAndSwap(String pk, List<ServingRow> rows, Instant asOf) {
            calls++;
            writes.put(pk, rows);
            return 1;
        }
    }
}
