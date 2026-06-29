package com.topsales.forecast.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class FoldSplitterTest {

    private static final CvConfig CV = CvConfig.defaults(); // 84 / 7 / 7 / 12
    private static final LocalDate START = LocalDate.of(2025, 1, 6); // a Monday

    @Test
    void a168DayRangeYieldsExactlyTwelveFoldsWithExactBoundaries() {
        // 168 inclusive days: [START, START+167].
        LocalDate end = START.plusDays(167);

        List<Fold> folds = FoldSplitter.split(START, end, CV);

        assertThat(folds).hasSize(12);

        // Fold 0: train [START, START+83], test [START+84, START+90].
        Fold f0 = folds.get(0);
        assertThat(f0.index()).isZero();
        assertThat(f0.trainStart()).isEqualTo(START);
        assertThat(f0.trainEnd()).isEqualTo(START.plusDays(83));
        assertThat(f0.testStart()).isEqualTo(START.plusDays(84));
        assertThat(f0.testEnd()).isEqualTo(START.plusDays(90));
        assertThat(f0.trainDays()).isEqualTo(84);
        assertThat(f0.testHorizonDays()).isEqualTo(7);

        // Fold 11 (last): train end grows by 11·7=77 days; test ends exactly on the range end.
        Fold f11 = folds.get(11);
        assertThat(f11.index()).isEqualTo(11);
        assertThat(f11.trainStart()).isEqualTo(START); // anchor never moves
        assertThat(f11.trainEnd()).isEqualTo(START.plusDays(83 + 77));
        assertThat(f11.testStart()).isEqualTo(START.plusDays(84 + 77));
        assertThat(f11.testEnd()).isEqualTo(end);
    }

    @Test
    void trainingWindowExpandsAndTestSpansAreContiguousAndNonOverlapping() {
        List<Fold> folds = FoldSplitter.split(START, START.plusDays(167), CV);

        for (int k = 1; k < folds.size(); k++) {
            Fold prev = folds.get(k - 1);
            Fold cur = folds.get(k);
            assertThat(cur.trainStart()).isEqualTo(prev.trainStart()); // expanding, not sliding
            assertThat(cur.trainDays()).isEqualTo(prev.trainDays() + CV.stepDays());
            // Every test day is strictly after its own training end.
            assertThat(cur.testStart()).isAfter(cur.trainEnd());
        }
    }

    @Test
    void historyShorterThanInitialTrainPlusHorizonYieldsZeroFolds() {
        // initialTrain(84) + horizon(7) = 91 days are required for even one fold; give 90.
        LocalDate end = START.plusDays(89); // 90 inclusive days

        assertThat(FoldSplitter.split(START, end, CV)).isEmpty();
    }

    @Test
    void exactlyEnoughHistoryYieldsOneFoldAndATrailingPartialIsDropped() {
        // 91 inclusive days -> exactly one full fold; the would-be 2nd fold's test overruns the end.
        LocalDate end = START.plusDays(90);

        List<Fold> folds = FoldSplitter.split(START, end, CV);

        assertThat(folds).hasSize(1);
        assertThat(folds.get(0).testEnd()).isEqualTo(end);
    }

    @Test
    void foldCountIsCappedAtMaxFolds() {
        // A long range could support many folds; the cap holds at maxFolds.
        List<Fold> folds = FoldSplitter.split(START, START.plusDays(1000), CV);

        assertThat(folds).hasSize(CV.maxFolds());
    }
}
