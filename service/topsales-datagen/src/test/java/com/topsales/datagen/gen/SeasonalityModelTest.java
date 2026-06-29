package com.topsales.datagen.gen;

import static org.assertj.core.api.Assertions.assertThat;

import com.topsales.common.domain.AggregateRow;
import com.topsales.common.domain.Channel;
import com.topsales.datagen.SeedConfig;
import com.topsales.datagen.SeedConfig.CategorySpec;
import com.topsales.datagen.SeedConfig.HveSpec;
import com.topsales.datagen.SeedConfig.OutlierSpec;
import com.topsales.datagen.SeedConfig.SeasonalitySpec;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Determinism + the demonstrable data features baked into the generator. */
class SeasonalityModelTest {

    private static final LocalDate START = LocalDate.of(2025, 3, 3); // a Monday
    private static final LocalDate END = START.plusDays(59);
    private static final LocalDate PLAIN_DAY = LocalDate.of(2025, 3, 10); // not an HVE date

    private static final CategorySpec ELECTRONICS =
            new CategorySpec("cat_electronics", 1000, 180, 0.7, false);
    private static final CategorySpec COLLECTIBLES =
            new CategorySpec("cat_collectibles", 60, 240, 0.8, true);

    private static final SeasonalitySpec SEASONALITY =
            new SeasonalitySpec(
                    new double[] {1.00, 1.05, 1.08, 1.10, 1.18, 1.12, 0.95},
                    new double[] {0.85, 0.88, 0.92, 1.00, 1.20, 1.45, 1.20},
                    new double[] {0.88, 0.86, 0.96, 1.00, 1.04, 0.98, 1.10, 1.00, 0.98, 1.06, 1.20, 1.25},
                    0.2,
                    0.18);
    private static final HveSpec HVE =
            new HveSpec(5.0, 3.0, 1.5, 6.0, 4.0, 1.5, 1.3, 2.0, 0.7);

    private SeedConfig config() {
        return new SeedConfig(
                42L,
                List.of("t_demo"),
                60,
                0.15,
                0.07,
                List.of(ELECTRONICS, COLLECTIBLES),
                new OutlierSpec("cat_home", "ONLINE", 70, 10),
                SEASONALITY,
                HVE);
    }

    private SeasonalityModel model() {
        return new SeasonalityModel(config(), "t_demo", "USD", START, END);
    }

    @Test
    void generationIsDeterministic_byteIdenticalAcrossRuns() {
        assertThat(model().generateAggregates()).isEqualTo(model().generateAggregates());
    }

    @Test
    void differentTenants_getIndependentData() {
        // The per-cell RNG keys on the tenant id, so two tenants get distinct (isolated) series.
        SeasonalityModel demo = new SeasonalityModel(config(), "t_demo", "USD", START, END);
        SeasonalityModel acme = new SeasonalityModel(config(), "t_acme", "USD", START, END);
        assertThat(demo.grossValue(ELECTRONICS, Channel.ONLINE, PLAIN_DAY))
                .isNotEqualTo(acme.grossValue(ELECTRONICS, Channel.ONLINE, PLAIN_DAY));
    }

    @Test
    void returnsAreNettedIntoTheAggregate() {
        SeasonalityModel m = model();
        double gross = m.grossValue(ELECTRONICS, Channel.ONLINE, PLAIN_DAY);
        BigDecimal expectedNet =
                BigDecimal.valueOf(gross * (1.0 - 0.07)).setScale(2, RoundingMode.HALF_UP);

        AggregateRow row =
                m.generateAggregates().stream()
                        .filter(
                                r ->
                                        r.categoryId().equals("cat_electronics")
                                                && r.channel() == Channel.ONLINE
                                                && r.bucketDate().equals(PLAIN_DAY))
                        .findFirst()
                        .orElseThrow();

        assertThat(row.sumAmount()).isEqualByComparingTo(expectedNet);
        assertThat(row.orderCount()).isGreaterThanOrEqualTo(1);
        assertThat(row.currency()).isEqualTo("USD");
    }

    @Test
    void sparseCategory_isIntermittent_mostDaysEmpty() {
        long days = END.toEpochDay() - START.toEpochDay() + 1; // 60
        long cells = days * 2; // two channels

        long sparseRows =
                model().generateAggregates().stream()
                        .filter(r -> r.categoryId().equals("cat_collectibles"))
                        .count();
        long denseRows =
                model().generateAggregates().stream()
                        .filter(r -> r.categoryId().equals("cat_electronics"))
                        .count();

        assertThat(denseRows).isEqualTo(cells); // dense category fires every day
        assertThat(sparseRows).isGreaterThan(0).isLessThan(cells / 2); // intermittent
    }

    @Test
    void hveSpike_showsUpInTheData() {
        // Black Friday produces a large spike vs the same weekday two weeks earlier (same weekly +
        // monthly factors, no HVE) — isolating the ~×5 offline BF uplift against ±10% noise.
        LocalDate bf = HveCalendar.blackFriday(2025);
        SeasonalityModel m =
                new SeasonalityModel(config(), "t_demo", "USD", bf.minusDays(30), bf.plusDays(5));

        double spike = m.grossValue(ELECTRONICS, Channel.OFFLINE, bf);
        double ordinary = m.grossValue(ELECTRONICS, Channel.OFFLINE, bf.minusDays(14));
        assertThat(spike).isGreaterThan(ordinary * 2);
    }
}
