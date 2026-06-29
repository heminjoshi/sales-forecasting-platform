package com.topsales.forecast.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.Channel;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class WapeCalculatorTest {

    private static final SeriesKey KEY = new SeriesKey("t1", "cat-1", Channel.ONLINE);
    private static final double EPS = 1e-9;

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void handComputedWapeAndBias() {
        // actuals {100,200,300} vs forecasts {110,180,330}:
        //   Σ|a−f| = 10+20+30 = 60 ; Σ|a| = 600  -> WAPE = 60/600 = 0.10
        //   Σ(f−a) = 10−20+30 = 20             -> bias = 20/600 ≈ 0.0333
        WapeCalculator calc = new WapeCalculator();
        calc.add(bd(100), bd(110));
        calc.add(bd(200), bd(180));
        calc.add(bd(300), bd(330));

        SegmentMetrics m = calc.toMetrics(KEY, "test");

        assertThat(m.defined()).isTrue();
        assertThat(m.n()).isEqualTo(3);
        assertThat(m.wape()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(EPS));
        assertThat(m.bias()).isCloseTo(20.0 / 600.0, org.assertj.core.data.Offset.offset(EPS));
    }

    @Test
    void allZeroActualsIsUndefinedAndExcludedFromAPooledRollup() {
        WapeCalculator zeroSeg = new WapeCalculator();
        zeroSeg.add(bd(0), bd(5));
        zeroSeg.add(bd(0), bd(0));

        SegmentMetrics m = zeroSeg.toMetrics(KEY, "test");
        assertThat(m.defined()).isFalse();
        assertThat(m.wape()).isNaN();
        assertThat(m.bias()).isNaN();

        // A pooled rollup must skip undefined segments — merging only the defined one is exact.
        WapeCalculator defined = new WapeCalculator();
        defined.add(bd(100), bd(110));
        defined.add(bd(200), bd(180));
        defined.add(bd(300), bd(330));

        WapeCalculator pool = new WapeCalculator();
        if (defined.defined()) {
            pool.merge(defined);
        }
        if (zeroSeg.defined()) { // false -> not merged
            pool.merge(zeroSeg);
        }

        assertThat(pool.wape()).isCloseTo(0.10, org.assertj.core.data.Offset.offset(EPS));
        assertThat(pool.count()).isEqualTo(3); // the all-zero pair count never leaked in
    }

    @Test
    void aSingleInteriorZeroDayIsHarmless() {
        // One real no-sale day among nonzero days just contributes its |error| and 0 to Σ|a|.
        WapeCalculator calc = new WapeCalculator();
        calc.add(bd(100), bd(100));
        calc.add(bd(0), bd(0)); // no sale, predicted no sale -> zero error, harmless
        calc.add(bd(100), bd(100));

        SegmentMetrics m = calc.toMetrics(KEY, "test");
        assertThat(m.defined()).isTrue();
        assertThat(m.wape()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(EPS));
        assertThat(m.n()).isEqualTo(3);
    }

    @Test
    void poolingIsRatioOfSumsNotMeanOfPerSegmentWapes() {
        // Segment A: small volume, perfect. Segment B: large volume, 50% error.
        // Mean-of-WAPEs would be (0 + 0.5)/2 = 0.25; ratio-of-sums is volume-weighted ≈ 0.4762.
        WapeCalculator a = new WapeCalculator();
        a.add(bd(10), bd(10));

        WapeCalculator b = new WapeCalculator();
        b.add(bd(1000), bd(1500)); // |err| 500 over |a| 1000

        WapeCalculator pool = new WapeCalculator();
        pool.merge(a);
        pool.merge(b);

        assertThat(pool.wape()).isCloseTo(500.0 / 1010.0, org.assertj.core.data.Offset.offset(EPS));
    }
}
