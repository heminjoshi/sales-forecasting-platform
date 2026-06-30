package com.topsales.forecast.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Channel;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Always-on guard (runs in {@code make test}, pure JVM) that pins the ADR-0005 "baseline-beat" story
 * on the regenerated seed (fixed window): Holt-Winters is accurate on a dense seasonal series and
 * beats seasonal-naive there, while both degrade sharply on the sparse intermittent category.
 *
 * <p>Thresholds mirror {@code topsales.forecast.eval.wape-dense-max} (0.20) /
 * {@code wape-sparse-min} (0.40) and are kept loose on purpose — the observed numbers
 * (dense HW ≈ 0.057, sparse HW ≈ 1.6) sit far inside them, so the test asserts the <em>ordering and
 * direction</em> of the story, not brittle exact values.
 */
class BacktestRegressionTest {

    private static final double WAPE_DENSE_MAX = 0.20;
    private static final double WAPE_SPARSE_MIN = 0.40;

    // A dense, strongly seasonal series and a sparse intermittent one (same tenant).
    private static final SeriesKey DENSE =
            new SeriesKey("tenant_a", "cat_electronics", Channel.ONLINE);
    private static final SeriesKey SPARSE =
            new SeriesKey("tenant_a", "cat_collectibles", Channel.ONLINE);

    private static EvalResult result;

    @BeforeAll
    static void runBacktest() {
        Path repoRoot = EvalMain.repoRoot();
        result = BacktestRunner.withDefaults(repoRoot).run();
    }

    @Test
    void holtWintersIsAccurateOnADenseSeasonalSeries() {
        SegmentMetrics hw = segment(DENSE, BacktestRunner.MODEL_HW);
        assertThat(hw.defined()).isTrue();
        assertThat(hw.wape())
                .as("HW WAPE on dense %s", DENSE)
                .isLessThan(WAPE_DENSE_MAX);
    }

    @Test
    void holtWintersBeatsSeasonalNaiveOnTheDenseSeries() {
        double hw = segment(DENSE, BacktestRunner.MODEL_HW).wape();
        double naive = segment(DENSE, BacktestRunner.MODEL_NAIVE).wape();
        assertThat(hw).as("HW should beat SeasonalNaive on dense").isLessThan(naive);
    }

    @Test
    void bothModelsDegradeOnTheSparseIntermittentCategory() {
        SegmentMetrics hw = segment(SPARSE, BacktestRunner.MODEL_HW);
        SegmentMetrics naive = segment(SPARSE, BacktestRunner.MODEL_NAIVE);
        assertThat(hw.defined()).isTrue();
        assertThat(naive.defined()).isTrue();
        assertThat(hw.wape()).as("HW WAPE on sparse %s", SPARSE).isGreaterThan(WAPE_SPARSE_MIN);
        assertThat(naive.wape()).as("naive WAPE on sparse %s", SPARSE).isGreaterThan(WAPE_SPARSE_MIN);
    }

    @Test
    void denseAccuracyIsFarBetterThanSparse() {
        double dense = segment(DENSE, BacktestRunner.MODEL_HW).wape();
        double sparse = segment(SPARSE, BacktestRunner.MODEL_HW).wape();
        assertThat(dense).as("dense WAPE must be well below sparse WAPE").isLessThan(sparse);
    }

    @Test
    void holtWintersWinsTheOverallPooledRollup() {
        double hw = result.pooled(BacktestRunner.MODEL_HW).orElseThrow().wape();
        double naive = result.pooled(BacktestRunner.MODEL_NAIVE).orElseThrow().wape();
        assertThat(hw).as("HW pooled WAPE beats naive overall").isLessThan(naive);
    }

    private static SegmentMetrics segment(SeriesKey key, String model) {
        return result.segment(key, model)
                .orElseThrow(() -> new AssertionError("missing segment " + key + " / " + model));
    }
}
